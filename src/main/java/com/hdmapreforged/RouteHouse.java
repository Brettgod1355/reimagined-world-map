package com.hdmapreforged;

import static com.hdmapreforged.RouteFeature.*;
import static com.hdmapreforged.RoutePanels.*;
import static com.hdmapreforged.SearchResults.*;

import com.hdmapreforged.route.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import javax.swing.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

/**
 * One's own house for routes, kept per account: looked through once per visit (its portals, nexus and such, and its
 * plan), its picture drawn at the surface map's edge, and its panel. Threads as {@link RouteFeature}'s.
 */
@Slf4j
@RequiredArgsConstructor
public final class RouteHouse
{
    private final RouteFeature routes;
    private final HouseTracker house;

    /**
     * Where a route tile is in the scene: the plan of one's house lies at {@link HousePlan#X}/{@link HousePlan#Y}
     * plus the house's own place, moved to where it is loaded now; null outside the scene, past its edge only as far as
     * an expanded scene loads.
     */
    LocalPoint local(WorldView view, int point)
    {
        int x = Tiles.x(point);
        int y = Tiles.y(point);
        // In an instance copying a place (the Watchtower's top floor): its tiles are the template's, wherever the
        // instance put them in the scene.
        int[] template = view.isInstance() ? InstanceCopies.template(x, y, Tiles.z(point)) : null;
        if (template != null)
        {
            for (WorldPoint at : WorldPoint.toLocalInstance(view, new WorldPoint(template[0], template[1], template[2])))
            {
                LocalPoint local = LocalPoint.fromWorld(view, at);
                if (local != null)
                {
                    return local;
                }
            }
            return null;
        }
        if (HousePlan.contains(x, y))
        {
            return house.own() ? LocalPoint.fromScene(x - HousePlan.X + houseX, y - HousePlan.Y + houseY, view) : null;
        }
        LocalPoint local = LocalPoint.fromWorld(view, x, y);
        if (local == null)
        {
            // Past the scene's edge, where an expanded scene (the GPU plugins' setting) draws the ground on: the
            // route goes on there too, as far as the game loaded.
            int beyond = routes.expandedTiles();
            int sx = x - view.getBaseX();
            int sy = y - view.getBaseY();
            if (beyond > 0 && sx >= -beyond && sy >= -beyond && sx < view.getSizeX() + beyond
                && sy < view.getSizeY() + beyond)
            {
                local = LocalPoint.fromScene(sx, sy, view);
            }
        }
        return local;
    }

    /**
     * Tiles from the plan to the scene as loaded now. The game centres the scene on where one stood as it loaded (a
     * teleport in, building mode), so the house lies elsewhere in it each time; the plan keeps its south-west room at
     * chunk 2. Client thread writes.
     */
    private volatile int houseX;

    private volatile int houseY;

    void placeHouse(WorldView view)
    {
        int[][][] chunks = view == null ? null : view.getInstanceTemplateChunks();
        int west = 13;
        int south = 13;
        for (int[][] plane : chunks == null ? new int[0][][] : chunks)
        {
            for (int x = 0; x < plane.length; x++)
            {
                for (int y = 0; y < plane[x].length; y++)
                {
                    // A room of the house: copied from the game's house templates.
                    int data = plane[x][y];
                    if (data != -1 && HouseTracker.isTemplate((data >> 14 & 0x3ff) * 8, (data >> 3 & 0x7ff) * 8))
                    {
                        west = Math.min(west, x);
                        south = Math.min(south, y);
                    }
                }
            }
        }
        houseX = west < 13 ? (west - 2) * 8 : 0;
        houseY = south < 13 ? (south - 2) * 8 : 0;
    }

    /** The player's tile on the plan of their own house once seen; else -1 (the route starts from its ways out). */
    int inHouse()
    {
        Player player = routes.client.getLocalPlayer();
        LocalPoint local = player == null ? null : player.getLocalLocation();
        WorldView view = routes.client.getTopLevelWorldView();
        return plan == null || !house.own() || local == null || view == null ? -1 : planNode(local, view.getPlane());
    }

    /** A scene tile of one's house on its plan. */
    int planNode(LocalPoint local, int plane)
    {
        return HousePlan.node(local.getSceneX() - houseX, local.getSceneY() - houseY, plane);
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        // Not while a scene loads: the scan sees those, and going into building mode its spaces load before it shows.
        if (house.own() && routes.client.getGameState() == GameState.LOGGED_IN)
        {
            house.seen(name(event.getGameObject().getId()));
        }
    }

    private String name(int id)
    {
        ObjectComposition object = routes.client.getObjectDefinition(id);
        if (object != null && object.getImpostorIds() != null)
        {
            object = object.getImpostor();
        }
        return object == null || "null".equals(object.getName()) ? null : object.getName();
    }

    /** The game's list of portal nexus teleports (an enum of structs), and the param naming each. */
    private static final int NEXUS_TELEPORTS = 1377;

    private static final int NEXUS_NAME = 660;

    /** The nexus' 45 slots: runs of varbits one after another, from each run's first. */
    private static final int[][] NEXUS_SLOTS = {{VarbitID.POH_NEXUS_TELE_1, 15}, {VarbitID.POH_NEXUS_TELE_16, 3},
        {VarbitID.POH_NEXUS_TELE_19, 12}, {VarbitID.POH_NEXUS_TELE_31, 5}, {VarbitID.POH_NEXUS_TELE_36, 10}};

    private static final Pattern STAIRS = Pattern.compile(".*(tair|adder|rapdoor).*");

    /**
     * One's own house, looked through once per visit (its objects spawn while it loads, before a tick finds the player
     * inside): its portals, nexus and such for routes, and its plan, drawn at the surface map's edge. Kept per account.
     */
    void scanHouse()
    {
        WorldView view = routes.client.getTopLevelWorldView();
        Player player = routes.client.getLocalPlayer();
        LocalPoint me = player == null ? null : player.getLocalLocation();
        if (view == null || me == null)
        {
            house.scanAgain();
            return;
        }
        int size = HousePlan.SIZE;
        int dx = houseX;
        int dy = houseY;
        Tile[][][] scene = view.getScene().getTiles();
        CollisionData[] collision = view.getCollisionMaps();
        // Moved to the house's own place (houseX/houseY): the scene is centred on where one stood as it loaded.
        byte[][] placed = new byte[4][size * size];
        Map<Integer, String> things = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (int z = 0; z < 4; z++)
        {
            int[][] flags = collision == null || collision[z] == null ? new int[0][] : collision[z].getFlags();
            for (int x = Math.max(0, dx); x < Math.min(size, size + dx); x++)
            {
                for (int y = Math.max(0, dy); y < Math.min(size, size + dy); y++)
                {
                    Tile tile = scene[z][x][y];
                    // No ground (the void round the house) is no floor, though the game sets no flag there.
                    int f = tile == null || tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null
                        || x >= flags.length || y >= flags[x].length ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : flags[x][y];
                    int node = HousePlan.node(x - dx, y - dy, z);
                    placed[z][(x - dx) * size + y - dy] = (byte) (((f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0 ? 1 : 0)
                        | ((f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0 ? 2 : 0)
                        | ((f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0 ? 4 : 0));
                    GameObject[] standing = tile == null ? new GameObject[0] : tile.getGameObjects();
                    for (int k = 0; tile != null && k < standing.length + 2; k++)
                    {
                        // A mounted glory hangs on a wall.
                        TileObject object = k < standing.length ? standing[k]
                            : k == standing.length ? tile.getWallObject() : tile.getDecorativeObject();
                        // Once per object, at its south-west tile.
                        Point base = object instanceof GameObject ? ((GameObject) object).getSceneMinLocation() : null;
                        String name = object == null || base != null && (base.getX() != x || base.getY() != y) ? null
                            : name(object.getId());
                        if (name == null)
                        {
                            continue;
                        }
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(" space"))
                        {
                            // Building mode's empty spaces (its varbit can come before the normal house loads).
                            house.scanAgain();
                            return;
                        }
                        names.add(name);
                        if (object instanceof WallObject && lower.contains("door"))
                        {
                            door(placed[z], x - dx, y - dy, ((WallObject) object).getOrientationA());
                        }
                        else if (lower.contains("nexus") || name.equals("Portal") || HouseTracker.feature(name) != null
                            || STAIRS.matcher(name).matches())
                        {
                            things.put(node, lower.contains("nexus") ? RouteSource.NEXUS : name);
                        }
                    }
                }
            }
        }
        house.replaceFeatures(Set.of());
        names.forEach(house::seen);
        things.replaceAll((node, name) -> name.equals(RouteSource.NEXUS) ? nexus() : name);
        int arrival = HousePlan.node(me.getSceneX() - dx, me.getSceneY() - dy, view.getPlane());
        List<Integer> from = new ArrayList<>(List.of(arrival));
        things.forEach((node, name) -> {
            for (int d = 0; STAIRS.matcher(name).matches() && d < 9; d++)
            {
                // From where the player stands; stairs lead to the floors above and below.
                from.add(Tiles.pack(Tiles.x(node) + d / 3 - 1, Tiles.y(node) + d % 3 - 1, Tiles.z(node)));
            }
        });
        HousePlan.keepReachable(placed, from);
        String exit = "Portal";
        for (Map.Entry<Integer, String> thing : things.entrySet())
        {
            for (int d = 1; exit.equals(thing.getValue()) && d <= 3; d++)
            {
                int north = thing.getKey() + d;
                int y = Tiles.y(north) - HousePlan.Y;
                if (y < size && (placed[Tiles.z(north)][(Tiles.x(north) - HousePlan.X) * size + y] & 1) != 0)
                {
                    arrival = north;
                    exit = "";
                }
            }
        }
        // A house may have several superior gardens: a spirit tree in one and a fairy ring in another count as both.
        Set<String> gardens = new HashSet<>();
        things.values().stream().filter(RouteHouse::garden).forEach(t -> gardens.add(HouseTracker.feature(t)));
        if (!gardens.isEmpty())
        {
            boolean both = gardens.size() > 1 || gardens.contains(HouseSettings.BOTH);
            Set<String> kept = new LinkedHashSet<>(house.features());
            kept.removeIf(Arrays.asList(HouseSettings.GARDENS)::contains);
            kept.add(both ? HouseSettings.BOTH : gardens.iterator().next());
            house.replaceFeatures(kept);
        }
        HousePlan seen = new HousePlan(placed, things, arrival);
        // The game's own minimap of each floor (only floors the house has: each picture is drawn for the whole scene).
        BufferedImage[] drawn = new BufferedImage[4];
        for (int z = 0; z < 4; z++)
        {
            byte[] floor = placed[z];
            SpritePixels minimap = IntStream.range(0, floor.length).anyMatch(i -> (floor[i] & 1) != 0)
                ? routes.client.drawInstanceMap(z) : null;
            drawn[z] = minimap == null ? null : minimap.toBufferedImage();
        }
        keepLook(drawn, placed, dx, dy);
        String text = seen.encode();
        if (text != null)
        {
            routes.configManager.setRSProfileConfiguration(HdMapReforgedConfig.GROUP, PLAN_KEY, text);
        }
        setPlan(seen);
        writeHouse(house.features());
    }

    private static boolean garden(String thing)
    {
        String feature = HouseTracker.feature(thing);
        return feature != null && Arrays.asList(HouseSettings.GARDENS).contains(feature);
    }

    /** A door counts as open (orientation 1 west, 2 north, 4 east, 8 south): its wall's bit becomes a door's. */
    private static void door(byte[] tiles, int x, int y, int orientation)
    {
        int wall = orientation == 2 || orientation == 8 ? 2 : orientation == 1 || orientation == 4 ? 4 : 0;
        int tx = orientation == 1 ? x - 1 : x;
        int ty = orientation == 8 ? y - 1 : y;
        int i = tx * HousePlan.SIZE + ty;
        if (wall != 0 && tx >= 0 && ty >= 0)
        {
            tiles[i] = (byte) (tiles[i] & ~wall | wall * 4);
        }
    }

    static int nexusSlot(int slot)
    {
        for (int[] run : NEXUS_SLOTS)
        {
            if (slot < run[1])
            {
                return run[0] + slot;
            }
            slot -= run[1];
        }
        return -1;
    }

    /** "Portal Nexus: Varrock, Falador": its places from the game's varbits (slot, teleport, its name). */
    private String nexus()
    {
        List<String> places = new ArrayList<>();
        EnumComposition teleports = routes.client.getEnum(NEXUS_TELEPORTS);
        for (int slot = 0; slot < 45 && teleports != null; slot++)
        {
            int id = routes.client.getVarbitValue(nexusSlot(slot));
            int struct = id <= 0 ? -1 : teleports.getIntValue(id);
            StructComposition teleport = struct < 0 ? null : routes.client.getStructComposition(struct);
            String name = teleport == null ? null : teleport.getStringValue(NEXUS_NAME);
            String place = name == null ? null : Text.removeTags(name);
            if (place != null && !place.isEmpty())
            {
                places.add(place);
                house.seen(RouteSource.NEXUS + ": " + place);
            }
        }
        return RouteSource.NEXUS + (places.isEmpty() ? "" : ": " + String.join(", ", places));
    }

    /** Which house a house portal leads to, as the player chose it there. */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        Widget widget = event.getWidget();
        String what = (event.getMenuOption() + " " + Text.removeTags(event.getMenuTarget()) + " " + (widget == null
            || widget.getText() == null ? "" : Text.removeTags(widget.getText()))).toLowerCase(Locale.ROOT);
        // Also a tablet, the spell or a cape into it (not their Outside): at a portal that would look like a friend's.
        if (what.startsWith("home ") || what.startsWith("build mode ") || what.contains("your house")
            || what.startsWith("tele to poh") || what.matches("(break|inside|cast) teleport to house.*"))
        {
            house.chose(true);
        }
        else if (what.contains("friend's house"))
        {
            house.chose(false);
        }
    }

    /**
     * The house per floor as the game's minimap draws it, 4 pixels a tile over the scene's 104, north up; null where
     * not seen. Kept per account as one picture in the plugin's folder, the floors one under another.
     */
    private final AtomicReference<BufferedImage[]> houseLook = new AtomicReference<>(new BufferedImage[4]);

    private static final int LOOK = HousePlan.SIZE * 4;

    private volatile Filepath houseFolder;

    private volatile Executor houseIo;

    void setHouseFiles(Filepath folder, Executor io)
    {
        houseFolder = folder;
        houseIo = io;
    }

    private Filepath lookFile(long account)
    {
        Filepath folder = houseFolder;
        return folder == null || houseIo == null ? null : folder.joinSegment(Long.toHexString(account) + ".png");
    }

    /**
     * The house in a minimap picture that may cover an expanded scene, moved as the plan is ({@code dx}, {@code dy}
     * tiles from the plan to the scene): of the margins a chunk apart, the one where the plan's walls are the
     * picture's white lines (else its floor is painted); null when none fits. Black (no ground) becomes clear.
     */
    static BufferedImage sceneOf(BufferedImage all, byte[] floor, int dx, int dy)
    {
        BufferedImage best = null;
        int most = 0;
        for (int margin = 0; margin + LOOK <= Math.min(all.getWidth(), all.getHeight()); margin += 32)
        {
            int painted = 0;
            for (int i = 0; i < floor.length; i++)
            {
                int x = (i / HousePlan.SIZE + dx) * 4;
                int y = (HousePlan.SIZE - 1 - i % HousePlan.SIZE - dy) * 4;
                boolean in = x >= 0 && y >= 0 && x + 4 <= LOOK && y + 4 <= LOOK;
                int rgb = in ? all.getRGB(margin + x + 2, margin + y + 2) : 0;
                painted += (floor[i] & 1) != 0 && rgb >>> 24 != 0 && (rgb & 0xffffff) != 0 ? 1 : 0;
                // A wall on the tile's north or east edge: white there.
                painted += in && (floor[i] & 2) != 0 && white(all.getRGB(margin + x + 1, margin + y)) ? 16 : 0;
                painted += in && (floor[i] & 4) != 0 && white(all.getRGB(margin + x + 3, margin + y + 1)) ? 16 : 0;
            }
            if (painted > most)
            {
                // A copy: the rest of the picture (four times its size) is not kept with it.
                most = painted;
                int[] pixels = new int[LOOK * LOOK];
                for (int i = 0; i < pixels.length; i++)
                {
                    int x = i % LOOK + dx * 4;
                    int y = i / LOOK - dy * 4;
                    int rgb = x < 0 || y < 0 || x >= LOOK || y >= LOOK ? 0 : all.getRGB(margin + x, margin + y);
                    pixels[i] = (rgb & 0xffffff) == 0 ? 0 : rgb;
                }
                best = new BufferedImage(LOOK, LOOK, BufferedImage.TYPE_INT_ARGB);
                best.setRGB(0, 0, LOOK, LOOK, pixels, 0, LOOK);
            }
        }
        return best;
    }

    private static boolean white(int rgb)
    {
        return (rgb >> 16 & 255) > 180 && (rgb >> 8 & 255) > 180 && (rgb & 255) > 180;
    }

    /**
     * Fitted to the plan ({@link #sceneOf}: up to 7 ms a floor, 200 ms the first time), shown and written on the file
     * thread: not on the client thread.
     */
    private void keepLook(BufferedImage[] drawn, byte[][] floors, int dx, int dy)
    {
        // A look read from the file meanwhile does not replace this one.
        BufferedImage[] pending = new BufferedImage[4];
        houseLook.set(pending);
        Filepath file = lookFile(houseOf);
        Executor io = houseIo;
        Runnable fit = () -> {
            BufferedImage[] look = new BufferedImage[4];
            for (int z = 0; z < 4; z++)
            {
                look[z] = drawn[z] == null ? null : sceneOf(drawn[z], floors[z], dx, dy);
            }
            // Not over a newer scan, nor for another account.
            if (houseLook.compareAndSet(pending, look))
            {
                SwingUtilities.invokeLater(routes::repaint);
                write(look, file);
            }
        };
        if (io == null)
        {
            fit.run();
        }
        else
        {
            later(io, fit);
        }
    }

    /** The plugin's io threads stop with it: work handed over after that is dropped. */
    private static void later(Executor io, Runnable work)
    {
        if (!TileCache.execute(io, work))
        {
            log.debug("Plugin stopped; house file work dropped");
        }
    }

    private static void write(BufferedImage[] look, Filepath file)
    {
        if (file != null)
        {
            BufferedImage all = new BufferedImage(LOOK, LOOK * 4, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = all.createGraphics();
            for (int z = 0; z < 4; z++)
            {
                g.drawImage(look[z], 0, z * LOOK, null);
            }
            g.dispose();
            // In memory: ImageIO would cache through a temporary file of its own.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try
            {
                try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes))
                {
                    ImageIO.write(all, "png", out);
                }
                TileCache.writeAtomically(file, bytes.toByteArray());
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Could not keep the house's look", e);
            }
        }
    }

    /** The account's kept look, read on the file thread; our own drawing of the plan until then. */
    private void readLook(long account)
    {
        BufferedImage[] none = new BufferedImage[4];
        houseLook.set(none);
        Filepath file = lookFile(account);
        if (file == null)
        {
            return;
        }
        later(houseIo, () -> {
            BufferedImage[] look = new BufferedImage[4];
            try (ImageInputStream in = file.isFile() ? new MemoryCacheImageInputStream(new ByteArrayInputStream(
                TileCache.read(file))) : null)
            {
                BufferedImage all = in == null ? null : ImageIO.read(in);
                for (int z = 0; all != null && all.getWidth() == LOOK && all.getHeight() == LOOK * 4 && z < 4; z++)
                {
                    look[z] = all.getSubimage(0, z * LOOK, LOOK, LOOK);
                }
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Could not read the house's look", e);
            }
            // Not over a scan made meanwhile, nor for another account.
            if (houseOf == account && houseLook.compareAndSet(none, look))
            {
                SwingUtilities.invokeLater(routes::repaint);
            }
        });
    }

    /** The account whose house is loaded (client thread); none after a log out. */
    private volatile long houseOf = Long.MIN_VALUE;

    /** For Swing: the features as last written. */
    private volatile Set<String> houseFeatures = Set.of();

    volatile HousePlan plan;

    private volatile CollisionMap collision;

    /** The plan and the route data (loaded meanwhile) are joined under this lock. */
    private synchronized void setPlan(HousePlan seen)
    {
        plan = seen;
        if (collision != null)
        {
            collision.setHouse(seen);
        }
        houseChanged();
    }

    /** This account's house; not before RuneLite knows the account's profile: that may come just after the login. */
    void loadHouse()
    {
        if (routes.configManager.getRSProfileKey() == null)
        {
            return;
        }
        houseOf = routes.client.getAccountHash();
        routes.planted.load(routes.configManager.getRSProfileConfiguration(HdMapReforgedConfig.GROUP,
            PlantedSpiritTrees.KEY));
        String saved = routes.configManager.getRSProfileConfiguration(HdMapReforgedConfig.GROUP, HOUSE_KEY);
        Set<String> features = new LinkedHashSet<>(Arrays.asList(saved == null ? new String[0] : saved.split(",")));
        features.remove("");
        house.reset();
        house.restore(features);
        readLook(houseOf);
        writeHouse(house.features());
        String text = routes.configManager.getRSProfileConfiguration(HdMapReforgedConfig.GROUP, PLAN_KEY);
        setPlan(text == null ? null : HousePlan.decode(text));
    }

    void writeHouse(Set<String> features)
    {
        houseFeatures = Set.copyOf(features);
        routes.configManager.setRSProfileConfiguration(HdMapReforgedConfig.GROUP, HOUSE_KEY, String.join(",", features));
        houseChanged();
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        routes.clientThread.invokeLater(() -> {
            if (routes.client.getGameState() == GameState.LOGGED_IN)
            {
                loadHouse();
            }
        });
    }

    /** One's house at the surface map's edge: its picture per floor, and what stands where. */
    void paintHouse(Graphics2D g, MapView.Projection p)
    {
        HousePlan seen = plan;
        g.setFont(FontManager.getRunescapeSmallFont());
        if (seen == null)
        {
            return;
        }
        // One picture a floor, the game's minimap of it: not thousands of shapes a frame.
        BufferedImage look = houseLook.get()[Math.max(0, Math.min(3, p.plane()))];
        // At the surface map's edge, where the map draws the house.
        int left = (int) Math.round(p.screenX(HousePlan.DRAWN_X));
        int top = (int) Math.round(p.screenY(HousePlan.DRAWN_Y + HousePlan.SIZE));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(look, left, top, (int) Math.round(p.screenX(HousePlan.DRAWN_X
            + HousePlan.SIZE)) - left, (int) Math.round(p.screenY(HousePlan.DRAWN_Y)) - top, null);
        seen.things.forEach((node, name) -> {
            String label = name.split(":")[0];
            float x = (float) RouteText.x(p, node) - g.getFontMetrics().stringWidth(label) / 2f;
            float y = (float) RouteText.y(p, node);
            if (Tiles.z(node) == p.plane())
            {
                g.setColor(Color.BLACK);
                g.drawString(label, x + 1, y + 1);
                g.setColor(Color.WHITE);
                g.drawString(label, x, y);
            }
        });
    }

    /** "Your house" for this account: what it has for routes, set here, and its plan. Swing thread. */
    private JComponent housePanel(int width)
    {
        JPanel panel = column();
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        panel.add(label("Your house", width, Font.BOLD, Color.WHITE));
        HousePlan seen = plan;
        Set<String> now = houseFeatures;
        if (collision == null || houseOf == Long.MIN_VALUE)
        {
            panel.add(label("Log in to see and set this account's house.", width, Font.PLAIN, NOTE));
            return panel;
        }
        Color gray = ColorScheme.LIGHT_GRAY_COLOR;
        panel.add(label(seen == null ? "Teleport into it once: its rooms, portals, nexus and jewellery box are read "
            + "then. Or set them here." : "As seen last time you were in it, for this account. Routes walk through it; "
            + "right-click in it for a route there.", width, Font.PLAIN, gray));
        JComboBox<String> box = new JComboBox<>(new String[]{"No jewellery box", "Basic jewellery box",
            "Fancy jewellery box", "Ornate jewellery box"});
        box.setSelectedIndex(Arrays.asList(HouseSettings.BOXES).indexOf(HouseSettings.box(now)));
        JCheckBox glory = new JCheckBox("Mounted amulet of glory", now.contains("glory"));
        // A superior garden has one of these.
        JComboBox<String> garden = new JComboBox<>(new String[]{"No fairy ring or spirit tree", "Fairy ring",
            "Spirit tree", "Both (a spiritual fairy tree, or two gardens)"});
        garden.setSelectedIndex(HouseSettings.garden(now));
        JCheckBox obelisk = new JCheckBox("Obelisk to the Wilderness", now.contains("obelisk"));
        obelisk.setToolTipText("Routes use it with the Hard Wilderness Diary, which lets you choose the obelisk it "
            + "leads to");
        JTextField portals = new JTextField(HouseSettings.places(now, HouseSettings.PORTAL));
        JTextField nexus = new JTextField(HouseSettings.places(now, HouseSettings.NEXUS));
        long account = houseOf;
        Runnable save = () -> editHouse(account, HouseSettings.features(HouseSettings.BOXES[box.getSelectedIndex()],
            glory.isSelected(), garden.getSelectedIndex(), obelisk.isSelected(), portals.getText(), nexus.getText()));
        box.addActionListener(e -> save.run());
        glory.addActionListener(e -> save.run());
        garden.addActionListener(e -> save.run());
        obelisk.addActionListener(e -> save.run());
        for (JTextField places : new JTextField[]{portals, nexus})
        {
            places.setToolTipText("Where they lead, as the spellbooks name them, with commas; Enter saves");
            FullMapWindow.typable(places);
            places.addActionListener(e -> save.run());
        }
        for (JComponent part : new JComponent[]{box, glory, garden, obelisk, label("Portals:", width, Font.PLAIN, gray),
            portals, label("Portal nexus:", width, Font.PLAIN, gray), nexus})
        {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            part.setOpaque(part instanceof JTextField);
            part.setMaximumSize(new Dimension(width, part.getPreferredSize().height));
            panel.add(part);
        }
        if (seen != null)
        {
            panel.add(Box.createVerticalStrut(6));
            panel.add(button("Show its plan",
                () -> routes.forScreens(screen -> screen.view().focus(worldPoint(seen.arrival)))));
        }
        return panel;
    }

    /** Dropped when another account (or none) is logged in than the panel was made for. */
    private void editHouse(long account, Set<String> features)
    {
        routes.clientThread.invokeLater(() -> {
            if (houseOf != account)
            {
                return;
            }
            house.replaceFeatures(features);
            writeHouse(features);
            routes.state = routes.capture();
            SwingUtilities.invokeLater(routes::settingsChanged);
        });
    }

    private final IntFunction<JComponent> houseSection = this::housePanel;

    void openHouse()
    {
        routes.forScreens(screen -> screen.showHouse(houseSection));
    }

    /** The maps painted again, and an open house panel built again with what is known now (a scan, the other map). */
    private void houseChanged()
    {
        SwingUtilities.invokeLater(() -> {
            routes.repaint();
            routes.forScreens(screen -> screen.updateHouse(houseSection));
        });
    }

    /** The route data, loaded meanwhile, joined with the plan under the plan's lock; null when stopped. */
    synchronized void setCollision(CollisionMap map)
    {
        collision = map;
        if (map != null)
        {
            map.setHouse(plan);
        }
    }

    /** No account's house (logged out, or stopped). Client thread. */
    void forget()
    {
        house.reset();
        houseOf = Long.MIN_VALUE;
        houseLook.set(new BufferedImage[4]);
        setPlan(null);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN && routes.client.getAccountHash() != houseOf)
        {
            loadHouse();
        }
        else if (event.getGameState() == GameState.LOADING)
        {
            house.reloaded();
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            forget();
        }
    }
}
