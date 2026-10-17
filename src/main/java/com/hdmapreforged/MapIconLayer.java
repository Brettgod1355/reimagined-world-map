package com.hdmapreforged;

import static com.hdmapreforged.PoiType.*;
import static java.lang.Math.*;

import lombok.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import net.runelite.api.coords.*;
import com.hdmapreforged.MapData.Icon;
import com.hdmapreforged.MapView.Projection;

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
    static final Color RING = new Color(255, 255, 255, 230);
    static final Color RING_SHADOW = new Color(0, 0, 0, 150);

    final MapView view;
    final BooleanSupplier enabled;
    /** By 64×64 region, so a frame or mouse move only looks near the view. */
    Map<Integer, List<Icon>> byRegion = new HashMap<>();
    /** One icon per spot with a game sprite, by region. */
    Map<Integer, List<Icon>> drawnByRegion = new HashMap<>();
    Map<Poi, List<Icon>> byPoi = new IdentityHashMap<>();
    /**
     * Game sprites scaled once per element and size, as zooming goes back and forth between a few sizes: made again at
     * each change of size, the first frame of each drew some 400 new images (60 ms on an accelerated pipeline).
     */
    final Map<Long, BufferedImage> scaled = new HashMap<>();
    /** Past about 4 MB of sprites the sizes kept start again. */
    int scaledPixels;
    static final int MAX_SCALED_PIXELS = 1 << 20;
    /** Made this frame: at a new size the rest are drawn at a size made before, and follow in the next frames. */
    int madeNow;
    boolean later;
    static final int MADE_PER_FRAME = 48;
    static final BasicStroke RING_SHADOW_STROKE = new BasicStroke(4f);
    static final BasicStroke RING_SELECTED = new BasicStroke(2.2f);
    static final BasicStroke RING_HOVERED = new BasicStroke(1.6f);
    /** Our icons standing on a game icon of the same thing, and our passages on the game's icon for that way in. */
    Map<Poi, Icon> covering = new IdentityHashMap<>();
    /** Icons made for the game's icons (not ours), for their labels. */
    Map<Poi, Icon> created = new IdentityHashMap<>();

    /** The size our icons are drawn at; the game's are drawn as large. */
    final IntSupplier size;

    /**
     * As large as ours at a zoom, never smaller than {@link #ICON_PIXELS} times the tiles' scale (above 1 where the map
     * enlarges its most detailed tiles), in whole times the game's 15 pixels: the nearest, at least once. Pixel art
     * stays even: at 20 a 15-pixel icon had every third pixel doubled, and made smaller it was smoothed.
     */
    double drawnSize(Projection projection)
    {
        double wanted = max(ICON_PIXELS * scale(projection.zoom()),
            size.getAsInt() * MapView.iconScale(projection.zoom()));
        return ICON_PIXELS * max(1, round(wanted / ICON_PIXELS));
    }

    static int region(int x, int y)
    {
        return (x >> 6) << 16 | (y >> 6) & 0xffff;
    }

    List<List<Icon>> near(Map<Integer, List<Icon>> grid, Projection p,
        double left, double top, double right, double bottom)
    {
        double scale = p.screenX(1) - p.screenX(0);
        double x0 = p.screenX(0);
        double y0 = p.screenY(0);
        int minX = (int) floor((left - x0) / scale) - 1;
        int maxX = (int) ceil((right - x0) / scale) + 1;
        int maxY = (int) ceil((y0 - top) / scale) + 1;
        int minY = (int) floor((y0 - bottom) / scale) - 1;
        var found = new ArrayList<List<Icon>>();
        if ((long) (maxX - minX) * (maxY - minY) > 4_000_000_000L)
        {
            found.addAll(grid.values());
            return found;
        }
        for (int rx = minX >> 6; rx <= maxX >> 6; rx++)
        {
            for (int ry = minY >> 6; ry <= maxY >> 6; ry++)
            {
                var list = grid.get(rx << 16 | ry & 0xffff);
                if (list != null)
                {
                    found.add(list);
                }
            }
        }
        return found;
    }

    /** Swing thread only. {@code hidden}: our places the map does not draw, the planner's passages among them. */
    void setIcons(List<Icon> icons, Collection<Poi> hidden)
    {
        var made = new IdentityHashMap<Poi, Icon>();
        var cover = new IdentityHashMap<Poi, Icon>();
        var grid = new HashMap<Integer, List<Icon>>();
        var drawnGrid = new HashMap<Integer, List<Icon>>();
        var poiIcons = new IdentityHashMap<Poi, List<Icon>>();
        var spots = new HashSet<Long>();
        for (var icon : icons)
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
        view.searchExtras = new ArrayList<>(made.keySet());
        view.repaint();
    }

    /** How far from our passage the game's icon for that way in may stand: its dungeon icons stand a tile off. */
    static final int WAY_IN_REACH = 1;

    /**
     * Of our passages the map does not draw (they serve routes and the search), those the game shows a way in or out
     * for, each with that icon of the game's: on the passage's tile, else on one beside it.
     */
    static Map<Poi, Icon> waysIn(List<Icon> icons, Collection<Poi> hidden)
    {
        var byTile = new HashMap<WorldPoint, Icon>();
        for (var icon : icons)
        {
            if (wayIn(icon))
            {
                byTile.putIfAbsent(icon.location, icon);
            }
        }
        var found = new IdentityHashMap<Poi, Icon>();
        for (var poi : hidden)
        {
            if (poi.type != DUNGEON_ENTRANCE && poi.type != MAP_EXIT)
            {
                continue;
            }
            var icon = byTile.get(poi.location);
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
    static boolean wayIn(Icon icon)
    {
        var type = icon.poi.type;
        return !icon.own && (type == MAP_LINK || type == DUNGEON_ENTRANCE
            || "Dungeon".equalsIgnoreCase(icon.kind) || "Map link".equalsIgnoreCase(icon.kind));
    }

    void paintLarger(Graphics2D g, Projection projection)
    {
        if (!showsAny(projection))
        {
            return;
        }
        double drawn = drawnSize(projection);
        int px = (int) round(drawn);
        if (px <= 0)
        {
            return;
        }
        double r = drawn / 2 + 1;
        var toggled = toggles();
        madeNow = 0;
        later = false;
        for (var list : near(drawnByRegion, projection, -r, -r, projection.width() + r,
            projection.height() + r))
        {
            for (var icon : list)
            {
                double sx = projection.screenX(icon.drawn.getX());
                double sy = projection.screenY(icon.drawn.getY());
                if (sx < -r - 2 || sy < -r - 2 || sx > projection.width() + r + 2 || sy > projection.height() + r + 2
                    || !toggled[layer(icon).ordinal()] || !onView(icon, projection) || hidden(icon))
                {
                    continue;
                }
                BufferedImage sprite = sized(icon.element, px);
                if (sprite == null)
                {
                    continue;
                }
                var c = center(icon, projection);
                g.drawImage(sprite, (int) round(c.getX() - sprite.getWidth() / 2.0),
                    (int) round(c.getY() - sprite.getHeight() / 2.0), null);
                Color dot = view.config.questStatus() ? quest(icon) : null;
                if (dot != null)
                {
                    var spot = PlayerMarker.circle(c.getX() + drawn * 0.35, c.getY() + drawn * 0.35,
                        max(3, drawn * 0.18));
                    g.setColor(dot);
                    g.fill(spot);
                    g.setColor(Color.BLACK);
                    g.draw(spot);
                }
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
    BufferedImage sized(int element, int px)
    {
        BufferedImage sprite = GameIconSprites.element(element);
        if (sprite == null)
        {
            return null;
        }
        int own = max(sprite.getWidth(), sprite.getHeight());
        int times = max(1, round((float) px / own));
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
        var g = out.createGraphics();
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

    static long key(int element, int times)
    {
        return (long) times << 32 | element & 0xffffffffL;
    }

    static int tileLevel(double zoom)
    {
        return (int) max(TileCache.MIN_ZOOM, min(TileCache.MAX_ZOOM, ceil(zoom - 1e-6)));
    }

    /**
     * Of the zoom's own tile level, not the coarser one a large window draws past the memory cap: our tiles hold no
     * icons for the sprites to cover, and following that level drew them up to twice as large, unevenly.
     */
    static double scale(double zoom)
    {
        return pow(2, zoom - tileLevel(zoom));
    }

    boolean shows(Icon icon, Projection projection)
    {
        return showsAny(projection) && view.toggled(layer(icon)) && onView(icon, projection) && !hidden(icon);
    }

    /** A quest start icon's dot (see {@link Unlocks#questColor}), else null. */
    Color quest(Icon icon)
    {
        var unlocks = view.unlocks;
        return icon.poi.type != QUEST_START || unlocks == null ? null : unlocks.questColor(icon.poi.name);
    }

    boolean hidden(Icon icon)
    {
        return quest(icon) == Color.GREEN && view.config.hideCompletedQuests();
    }

    /** The setting a game icon follows besides its own: a bank's that of the banks; a map link the dungeon entrances. */
    static Layer layer(Icon icon)
    {
        return icon.poi.type == MAP_LINK ? Layer.DUNGEONS : icon.poi.type.layer;
    }

    /** Each layer's setting, read once per frame rather than per icon. */
    boolean[] toggles()
    {
        boolean[] toggled = new boolean[Layer.values().length];
        for (Layer layer : Layer.values())
        {
            toggled[layer.ordinal()] = view.toggled(layer);
        }
        return toggled;
    }

    boolean showsAny(Projection projection)
    {
        return projection.map() != null && tileLevel(projection.zoom()) >= FIRST_LEVEL && enabled.getAsBoolean();
    }

    /** On the map in view and its floor, or on the ground floor's view a floor drawn flat on it. */
    static boolean onView(Icon icon, Projection projection)
    {
        var map = projection.map();
        return (map.id == BaseMap.FULL || icon.map == map)
            && (icon.drawn.getPlane() == projection.plane()
                // Ground floor tiles show the floors above; underground only where the game's map draws a place's
                // floors flat (Keldagrim's houses), floors on one spot being other places elsewhere.
                || projection.plane() == 0 && (icon.drawn.getY() < MapView.UNDERGROUND_Y || icon.flat));
    }

    /** {@link #covers} for a frame, the settings read once. */
    Predicate<Poi> covers(Projection projection)
    {
        if (!showsAny(projection))
        {
            return poi -> false;
        }
        var toggled = toggles();
        return poi -> {
            var icon = covering.get(poi);
            return icon != null && toggled[layer(icon).ordinal()] && GameIconSprites.element(icon.element) != null
                && onView(icon, projection);
        };
    }

    static Point2D center(Icon icon, Projection projection)
    {
        double offset = OFFSET_PIXELS * scale(projection.zoom());
        return new Point2D.Double(projection.screenX(icon.drawn.getX()) + offset,
            projection.screenY(icon.drawn.getY()) + offset);
    }

    public Poi hit(Point point, Projection projection)
    {
        var icon = iconAt(point, projection);
        return icon == null ? null : icon.poi;
    }

    /** The game icon under a point, nearest middle wins; null if none. */
    Icon iconAt(Point point, Projection projection)
    {
        if (!showsAny(projection))
        {
            return null;
        }
        double reach = drawnSize(projection) / 2 + 1;
        Icon best = null;
        double bestDistance = reach;
        var toggled = toggles();
        for (var list : near(byRegion, projection, point.x - reach - 8, point.y - reach - 8,
            point.x + reach + 8, point.y + reach + 8))
        {
            for (var icon : list)
            {
                double sx = projection.screenX(icon.drawn.getX());
                double sy = projection.screenY(icon.drawn.getY());
                if (abs(sx - point.x) > reach + 4 || abs(sy - point.y) > reach + 4
                    || !toggled[layer(icon).ordinal()] || !onView(icon, projection) || hidden(icon))
                {
                    continue;
                }
                var c = center(icon, projection);
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

    public Point2D labelAnchor(Poi poi, Projection projection)
    {
        var c = iconCenter(poi, projection);
        return c == null ? null : new Point2D.Double(c.getX(), c.getY() + drawnSize(projection) / 2 + 5);
    }

    public Point2D iconCenter(Poi poi, Projection projection)
    {
        var icon = created.get(poi);
        if (icon == null && covers(projection).test(poi))
        {
            icon = covering.get(poi);
        }
        return icon == null ? null : center(icon, projection);
    }

    /** Draws the game's sprites once read from the client; then rings the hovered and selected. */
    public void paint(Graphics2D g, Projection projection)
    {
        if (GameIconSprites.hasElements() && enabled.getAsBoolean())
        {
            paintLarger(g, projection);
        }
        var hovered = view.hovered();
        var selected = view.selected();
        if (hovered == null && selected == null)
        {
            return;
        }
        double r = drawnSize(projection) / 2 + 2.5;
        var ofSelected = byPoi.getOrDefault(selected, Collections.emptyList());
        var ringed = new ArrayList<Icon>(byPoi.getOrDefault(hovered, Collections.emptyList()));
        // The selected's last, on top. A passage's is the game's icon for it, which may be the one hovered.
        ringed.removeAll(ofSelected);
        ringed.addAll(ofSelected);
        for (var icon : ringed)
        {
            if (!shows(icon, projection))
            {
                continue;
            }
            var c = center(icon, projection);
            if (c.getX() < -r || c.getY() < -r || c.getX() > projection.width() + r || c.getY() > projection.height() + r)
            {
                continue;
            }
            var ring = PlayerMarker.circle(c.getX(), c.getY(), r);
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
