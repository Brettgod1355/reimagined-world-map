package com.hdmapreforged;

import lombok.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import net.runelite.api.coords.*;

/**
 * Draws the game's own map icons (their sprites, {@link GameIconSprites}) where places.json puts them, hoverable and
 * clickable; only the hovered or selected one gets a ring. Where one stands on an icon of ours for the same thing,
 * ours is not drawn ({@link #covers}); one for a way in stands for the planner's passage there too, ringed when that is
 * selected. They are at least 15 px, more where the map enlarges its most detailed tiles, centred 1.5 of the tiles'
 * pixels left of and above their spot's south-west corner, from tile level 0 in; the ground floor shows every floor's
 * above ground and where the game's map draws a place's floors flat (Keldagrim), upper floors only their own.
 */
@RequiredArgsConstructor
final class MapIconLayer implements MapView.Overlay, MapView.HitLayer
{
    static final double ICON_PIXELS = 15;
    static final double OFFSET_PIXELS = -1.5;
    static final int FIRST_LEVEL = 0;
    private static final Color RING = new Color(255, 255, 255, 230);
    private static final Color RING_SHADOW = new Color(0, 0, 0, 150);

    private final MapView view;
    private final BooleanSupplier enabled;
    /** By 64×64 region, so a frame or mouse move only looks near the view. */
    private Map<Integer, List<MapData.Icon>> byRegion = new HashMap<>();
    /** One icon per spot with a game sprite, by region. */
    private Map<Integer, List<MapData.Icon>> drawnByRegion = new HashMap<>();
    private Map<Poi, List<MapData.Icon>> byPoi = new IdentityHashMap<>();
    /**
     * Game sprites scaled once per element and size, as zooming goes back and forth between a few sizes: made again at
     * each change of size, the first frame of each drew some 400 new images (60 ms on an accelerated pipeline).
     */
    private final Map<Long, BufferedImage> scaled = new HashMap<>();
    /** Past about 4 MB of sprites the sizes kept start again. */
    private int scaledPixels;
    private static final int MAX_SCALED_PIXELS = 1 << 20;
    /** Made this frame: at a new size the rest are drawn at a size made before, and follow in the next frames. */
    private int madeNow;
    private boolean later;
    private static final int MADE_PER_FRAME = 48;
    private static final BasicStroke RING_SHADOW_STROKE = new BasicStroke(4f);
    private static final BasicStroke RING_SELECTED = new BasicStroke(2.2f);
    private static final BasicStroke RING_HOVERED = new BasicStroke(1.6f);
    /** Our icons standing on a game icon of the same thing, and our passages on the game's icon for that way in. */
    private Map<Poi, MapData.Icon> covering = new IdentityHashMap<>();
    /** Icons made for the game's icons (not ours), for their labels. */
    private Map<Poi, MapData.Icon> created = new IdentityHashMap<>();

    /** The size our icons are drawn at; the game's are drawn as large. */
    private final IntSupplier size;

    /**
     * As large as ours at a zoom, never smaller than {@link #ICON_PIXELS} times the tiles' scale (above 1 where the map
     * enlarges its most detailed tiles), in whole times the game's 15 pixels: the nearest, at least once. Pixel art
     * stays even: at 20 a 15-pixel icon had every third pixel doubled, and made smaller it was smoothed.
     */
    double drawnSize(MapView.Projection projection)
    {
        double wanted = Math.max(ICON_PIXELS * scale(projection.zoom()),
            size.getAsInt() * MapView.iconScale(projection.zoom()));
        return ICON_PIXELS * Math.max(1, Math.round(wanted / ICON_PIXELS));
    }

    double drawnRadius(MapView.Projection projection)
    {
        return drawnSize(projection) / 2;
    }

    private static int region(int x, int y)
    {
        return (x >> 6) << 16 | (y >> 6) & 0xffff;
    }

    private List<List<MapData.Icon>> near(Map<Integer, List<MapData.Icon>> grid, MapView.Projection p,
        double left, double top, double right, double bottom)
    {
        double scale = p.screenX(1) - p.screenX(0);
        double x0 = p.screenX(0);
        double y0 = p.screenY(0);
        int minX = (int) Math.floor((left - x0) / scale) - 1;
        int maxX = (int) Math.ceil((right - x0) / scale) + 1;
        int maxY = (int) Math.ceil((y0 - top) / scale) + 1;
        int minY = (int) Math.floor((y0 - bottom) / scale) - 1;
        List<List<MapData.Icon>> found = new ArrayList<>();
        if ((long) (maxX - minX) * (maxY - minY) > 4_000_000_000L)
        {
            found.addAll(grid.values());
            return found;
        }
        for (int rx = minX >> 6; rx <= maxX >> 6; rx++)
        {
            for (int ry = minY >> 6; ry <= maxY >> 6; ry++)
            {
                List<MapData.Icon> list = grid.get(rx << 16 | ry & 0xffff);
                if (list != null)
                {
                    found.add(list);
                }
            }
        }
        return found;
    }

    /** Swing thread only. {@code hidden}: our places the map does not draw, the planner's passages among them. */
    void setIcons(List<MapData.Icon> icons, Collection<Poi> hidden)
    {
        Map<Poi, MapData.Icon> made = new IdentityHashMap<>();
        Map<Poi, MapData.Icon> cover = new IdentityHashMap<>();
        Map<Integer, List<MapData.Icon>> grid = new HashMap<>();
        Map<Integer, List<MapData.Icon>> drawnGrid = new HashMap<>();
        Map<Poi, List<MapData.Icon>> poiIcons = new IdentityHashMap<>();
        Set<Long> spots = new HashSet<>();
        for (MapData.Icon icon : icons)
        {
            // Ours standing on the game's icon for it: the game's is drawn instead, once its sprite is there.
            (icon.own ? cover : made).put(icon.poi, icon);
            int key = region(icon.drawn.getX(), icon.drawn.getY());
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(icon);
            poiIcons.computeIfAbsent(icon.poi, k -> new ArrayList<>(1)).add(icon);
            long spot = (long) icon.drawn.getX() << 32 | (long) icon.drawn.getY() << 2 | icon.drawn.getPlane();
            if (icon.element >= 0 && spots.add(spot))
            {
                drawnGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(icon);
            }
        }
        // A passage of ours chosen (in the search, among a kind's places) rings the game's icon for that way in rather
        // than being drawn over it ("Mining Guild basement" drew the game's dungeon icon, an exclamation mark, on the
        // map link). A click on the game's icon still selects the game's own place, which knows where its link leads.
        waysIn(icons, hidden).forEach((passage, icon) -> {
            if (cover.putIfAbsent(passage, icon) == null)
            {
                poiIcons.computeIfAbsent(passage, k -> new ArrayList<>(1)).add(icon);
            }
        });
        covering = cover;
        created = made;
        byRegion = grid;
        drawnByRegion = drawnGrid;
        byPoi = poiIcons;
        scaled.clear();
        scaledPixels = 0;
        view.setSearchExtras(new ArrayList<>(made.keySet()));
        view.repaint();
    }

    /** How far from our passage the game's icon for that way in may stand: its dungeon icons stand a tile off. */
    static final int WAY_IN_REACH = 1;

    /**
     * Of our passages the map does not draw (they serve routes and the search), those the game shows a way in or out
     * for, each with that icon of the game's: on the passage's tile, else on one beside it.
     */
    static Map<Poi, MapData.Icon> waysIn(List<MapData.Icon> icons, Collection<Poi> hidden)
    {
        Map<WorldPoint, MapData.Icon> byTile = new HashMap<>();
        for (MapData.Icon icon : icons)
        {
            if (wayIn(icon))
            {
                byTile.putIfAbsent(icon.location, icon);
            }
        }
        Map<Poi, MapData.Icon> found = new IdentityHashMap<>();
        for (Poi poi : hidden)
        {
            if (poi.type != PoiType.DUNGEON_ENTRANCE && poi.type != PoiType.MAP_EXIT)
            {
                continue;
            }
            MapData.Icon icon = byTile.get(poi.location);
            for (int dx = -WAY_IN_REACH; icon == null && dx <= WAY_IN_REACH; dx++)
            {
                for (int dy = -WAY_IN_REACH; icon == null && dy <= WAY_IN_REACH; dy++)
                {
                    icon = byTile.get(poi.location.dx(dx).dy(dy));
                }
            }
            if (icon != null)
            {
                found.put(poi, icon);
            }
        }
        return found;
    }

    /** One of the game's own icons for a way in or out: a map link or dungeon icon, also one that knows no target. */
    private static boolean wayIn(MapData.Icon icon)
    {
        PoiType type = icon.poi.type;
        return !icon.own && (type == PoiType.MAP_LINK || type == PoiType.DUNGEON_ENTRANCE
            || "Dungeon".equalsIgnoreCase(icon.kind) || "Map link".equalsIgnoreCase(icon.kind));
    }

    private void paintLarger(Graphics2D g, MapView.Projection projection)
    {
        if (!showsAny(projection))
        {
            return;
        }
        double drawn = drawnSize(projection);
        int px = (int) Math.round(drawn);
        if (px <= 0)
        {
            return;
        }
        double r = drawn / 2 + 1;
        boolean[] toggled = toggles();
        madeNow = 0;
        later = false;
        for (List<MapData.Icon> list : near(drawnByRegion, projection, -r, -r, projection.width() + r,
            projection.height() + r))
        {
            for (MapData.Icon icon : list)
            {
                double sx = projection.screenX(icon.drawn.getX());
                double sy = projection.screenY(icon.drawn.getY());
                if (sx < -r - 2 || sy < -r - 2 || sx > projection.width() + r + 2 || sy > projection.height() + r + 2
                    || !toggled[layer(icon).ordinal()] || !onView(icon, projection))
                {
                    continue;
                }
                BufferedImage sprite = sized(icon.element, px);
                if (sprite == null)
                {
                    continue;
                }
                Point2D c = center(icon, projection);
                g.drawImage(sprite, (int) Math.round(c.getX() - sprite.getWidth() / 2.0),
                    (int) Math.round(c.getY() - sprite.getHeight() / 2.0), null);
            }
        }
        if (later)
        {
            view.repaint();
        }
    }

    /**
     * A game sprite at {@code px}: itself at its own size, else enlarged by whole times, once per size. Past a frame's
     * share, one already made at the next size down or up stands in: at a change of size making all at once drew some
     * 400 new images in one frame (55 ms on an accelerated pipeline, which uploads each).
     */
    private BufferedImage sized(int element, int px)
    {
        BufferedImage sprite = GameIconSprites.element(element);
        if (sprite == null)
        {
            return null;
        }
        int own = Math.max(sprite.getWidth(), sprite.getHeight());
        int times = Math.max(1, Math.round((float) px / own));
        if (times == 1)
        {
            return sprite;
        }
        BufferedImage cached = scaled.get(key(element, times));
        if (cached != null)
        {
            return cached;
        }
        if (madeNow >= MADE_PER_FRAME)
        {
            BufferedImage near = scaled.getOrDefault(key(element, times - 1), scaled.get(key(element, times + 1)));
            if (near != null || times == 2)
            {
                later = true;
                return near != null ? near : sprite;
            }
        }
        madeNow++;
        int w = sprite.getWidth() * times;
        int h = sprite.getHeight() * times;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(sprite, 0, 0, w, h, null);
        g.dispose();
        scaledPixels += w * h;
        if (scaledPixels > MAX_SCALED_PIXELS)
        {
            scaled.clear();
            scaledPixels = w * h;
        }
        scaled.put(key(element, times), out);
        return out;
    }

    private static long key(int element, int times)
    {
        return (long) times << 32 | element & 0xffffffffL;
    }

    static int tileLevel(double zoom)
    {
        return (int) Math.max(TileCache.MIN_ZOOM, Math.min(TileCache.MAX_ZOOM, Math.ceil(zoom - 1e-6)));
    }

    /**
     * Of the zoom's own tile level, not the coarser one a large window draws past the memory cap: our tiles hold no
     * icons for the sprites to cover, and following that level drew them up to twice as large, unevenly.
     */
    static double scale(double zoom)
    {
        return Math.pow(2, zoom - tileLevel(zoom));
    }

    boolean shows(MapData.Icon icon, MapView.Projection projection)
    {
        return showsAny(projection) && view.toggled(layer(icon)) && onView(icon, projection);
    }

    /** The setting a game icon follows besides its own: a bank's that of the banks; a map link the dungeon entrances. */
    private static Layer layer(MapData.Icon icon)
    {
        return icon.poi.type == PoiType.MAP_LINK ? Layer.DUNGEONS : icon.poi.type.layer;
    }

    /** Each layer's setting, read once per frame rather than per icon. */
    private boolean[] toggles()
    {
        boolean[] toggled = new boolean[Layer.values().length];
        for (Layer layer : Layer.values())
        {
            toggled[layer.ordinal()] = view.toggled(layer);
        }
        return toggled;
    }

    private boolean showsAny(MapView.Projection projection)
    {
        return projection.map() != null && tileLevel(projection.zoom()) >= FIRST_LEVEL && enabled.getAsBoolean();
    }

    /** On the map in view and its floor, or on the ground floor's view a floor drawn flat on it. */
    static boolean onView(MapData.Icon icon, MapView.Projection projection)
    {
        BaseMap map = projection.map();
        return (map.id == BaseMap.FULL || icon.map == map)
            && (icon.drawn.getPlane() == projection.plane()
                // Ground floor tiles show the floors above; underground only where the game's map draws a place's
                // floors flat (Keldagrim's houses), floors on one spot being other places elsewhere.
                || projection.plane() == 0 && (icon.drawn.getY() < MapView.UNDERGROUND_Y || icon.flat));
    }

    /** Whether the game's icon is drawn on this one of ours (its sprite loaded), which then stays away. */
    boolean covers(Poi poi, MapView.Projection projection)
    {
        MapData.Icon icon = covering.get(poi);
        return icon != null && GameIconSprites.element(icon.element) != null && shows(icon, projection);
    }

    /** {@link #covers} for a frame, the settings read once. */
    Predicate<Poi> covers(MapView.Projection projection)
    {
        if (!showsAny(projection))
        {
            return poi -> false;
        }
        boolean[] toggled = toggles();
        return poi -> {
            MapData.Icon icon = covering.get(poi);
            return icon != null && toggled[layer(icon).ordinal()] && GameIconSprites.element(icon.element) != null
                && onView(icon, projection);
        };
    }

    static Point2D center(MapData.Icon icon, MapView.Projection projection)
    {
        double offset = OFFSET_PIXELS * scale(projection.zoom());
        return new Point2D.Double(projection.screenX(icon.drawn.getX()) + offset,
            projection.screenY(icon.drawn.getY()) + offset);
    }

    @Override
    public Poi hit(Point point, MapView.Projection projection)
    {
        MapData.Icon icon = iconAt(point, projection);
        return icon == null ? null : icon.poi;
    }

    /** The game icon under a point, nearest middle wins; null if none. */
    MapData.Icon iconAt(Point point, MapView.Projection projection)
    {
        if (!showsAny(projection))
        {
            return null;
        }
        double reach = drawnRadius(projection) + 1;
        MapData.Icon best = null;
        double bestDistance = reach;
        boolean[] toggled = toggles();
        for (List<MapData.Icon> list : near(byRegion, projection, point.x - reach - 8, point.y - reach - 8,
            point.x + reach + 8, point.y + reach + 8))
        {
            for (MapData.Icon icon : list)
            {
                double sx = projection.screenX(icon.drawn.getX());
                double sy = projection.screenY(icon.drawn.getY());
                if (Math.abs(sx - point.x) > reach + 4 || Math.abs(sy - point.y) > reach + 4
                    || !toggled[layer(icon).ordinal()] || !onView(icon, projection))
                {
                    continue;
                }
                Point2D c = center(icon, projection);
                double d = c.distance(point);
                if (d <= bestDistance)
                {
                    best = icon;
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    @Override
    public Point2D labelAnchor(Poi poi, MapView.Projection projection)
    {
        Point2D c = iconCenter(poi, projection);
        return c == null ? null : new Point2D.Double(c.getX(), c.getY() + drawnRadius(projection) + 5);
    }

    @Override
    public Point2D iconCenter(Poi poi, MapView.Projection projection)
    {
        MapData.Icon icon = created.get(poi);
        if (icon == null && covers(poi, projection))
        {
            icon = covering.get(poi);
        }
        return icon == null ? null : center(icon, projection);
    }

    /** Draws the game's sprites once read from the client; then rings the hovered and selected. */
    @Override
    public void paint(Graphics2D g, MapView.Projection projection)
    {
        if (GameIconSprites.hasElements() && enabled.getAsBoolean())
        {
            paintLarger(g, projection);
        }
        Poi hovered = view.hovered();
        Poi selected = view.selected();
        if (hovered == null && selected == null)
        {
            return;
        }
        double r = drawnRadius(projection) + 2.5;
        List<MapData.Icon> ofSelected = byPoi.getOrDefault(selected, Collections.emptyList());
        List<MapData.Icon> ringed = new ArrayList<>(byPoi.getOrDefault(hovered, Collections.emptyList()));
        // The selected's last, on top. A passage's is the game's icon for it, which may be the one hovered.
        ringed.removeAll(ofSelected);
        ringed.addAll(ofSelected);
        for (MapData.Icon icon : ringed)
        {
            if (!shows(icon, projection))
            {
                continue;
            }
            Point2D c = center(icon, projection);
            if (c.getX() < -r || c.getY() < -r || c.getX() > projection.width() + r || c.getY() > projection.height() + r)
            {
                continue;
            }
            Ellipse2D ring = new Ellipse2D.Double(c.getX() - r, c.getY() - r, r * 2, r * 2);
            g.setStroke(RING_SHADOW_STROKE);
            g.setColor(RING_SHADOW);
            g.draw(ring);
            boolean chosen = ofSelected.contains(icon);
            g.setStroke(chosen ? RING_SELECTED : RING_HOVERED);
            g.setColor(chosen ? PlayerMarker.YELLOW : RING);
            g.draw(ring);
        }
    }
}
