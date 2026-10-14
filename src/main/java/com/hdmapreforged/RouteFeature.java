package com.hdmapreforged;

import static com.hdmapreforged.PlayerMarker.circle;
import static com.hdmapreforged.RoutePanels.*;
import static com.hdmapreforged.SearchResults.*;

import com.hdmapreforged.route.*;
import com.hdmapreforged.route.Route.*;
import com.hdmapreforged.route.Route.Step.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.*;

/**
 * "Path to here": our own route search; only shows a way, never walks, clicks or sends anything.
 * Threads: player state on the client thread; searches on their own thread, extra work on the route extras thread;
 * the controller and map drawing on Swing.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class RouteFeature
{
    /** Per account: the house's features ("box:ornate,glory,portal:Varrock") and its plan. */
    static final String HOUSE_KEY = "routeHouse";
    static final String PLAN_KEY = "housePlan";
    private static final int[] BOAT_PORTS = {VarbitID.SAILING_BOAT_1_PORT, VarbitID.SAILING_BOAT_2_PORT,
        VarbitID.SAILING_BOAT_3_PORT, VarbitID.SAILING_BOAT_4_PORT, VarbitID.SAILING_BOAT_5_PORT};
    /** The same boats' teleport focus: 0 none, 1 teleport focus, 2 greater (the game's boat spells test 1 and 2). */
    private static final int[] BOAT_FOCUS = {VarbitID.SAILING_BOAT_1_TELEPORT_FOCUS,
        VarbitID.SAILING_BOAT_2_TELEPORT_FOCUS, VarbitID.SAILING_BOAT_3_TELEPORT_FOCUS,
        VarbitID.SAILING_BOAT_4_TELEPORT_FOCUS, VarbitID.SAILING_BOAT_5_TELEPORT_FOCUS};
    private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2,
        VarbitID.RUNE_POUCH_TYPE_3, VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
    private static final int[] POUCH_AMOUNTS = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
        VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5,
        VarbitID.RUNE_POUCH_QUANTITY_6};
    private static final Set<Integer> RUNE_POUCHES = Set.of(12791, 24416, 27281, 27509);
    private static final int STATE_TICKS = 3;
    private static final int QUEST_TICKS = 100;

    static final Color WALK = new Color(255, 233, 28);
    static final Color JUMP = new Color(190, 120, 255);
    private static final Set<String> LOOK_KEYS = Set.of("routeColor", "routeJumpColor", "routeTileFill",
        "routeTileBorder", "routeTileWidth", "routeMinimap", "routeInGame", "routeHdTiles", "routeClearOnArrival",
        "routeFollowPlugins");

    private static final Color[] LEG_COLORS = {
        new Color(90, 220, 130), new Color(255, 130, 200), new Color(255, 160, 70), new Color(120, 230, 255),
        new Color(200, 255, 110), new Color(255, 255, 255), new Color(180, 150, 255)};

    static final Color CANNOT = new Color(255, 112, 112);
    private static final Color SP_WALK = new Color(255, 214, 64);
    private static final Color SHADOW = new Color(0, 0, 0, 130);

    private Color walk()
    {
        return opaque(config.routeColor(), WALK);
    }

    private Color jump()
    {
        return opaque(config.routeJumpColor(), JUMP);
    }

    private static Color opaque(Color c, Color fallback)
    {
        return c == null ? fallback : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    static WorldPoint worldPoint(int node)
    {
        return new WorldPoint(Tiles.x(node), Tiles.y(node), Tiles.z(node));
    }

    private static int pack(WorldPoint p)
    {
        return Tiles.pack(p.getX(), p.getY(), p.getPlane());
    }

    private static Color alpha(Color c, float alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(alpha))));
    }

    /** A route tile for HD World Markers; {@code alpha}: how visible (fading); null where it cannot be drawn. */
    private TileMarkersBridge.Tile hdTile(int point, float alpha, Color walk)
    {
        WorldPoint at = markerPoint(point);
        return at == null ? null : new TileMarkersBridge.Tile(at, alpha(walk, tileBorder * alpha),
            alpha(walk, tileFill * alpha), tileBorder == 0 ? 0 : tileWidth, null);
    }

    /**
     * Where HD World Markers draws a route tile. A tile of one's house plan is sent as the one instance tile of the
     * scene as loaded now (WorldPoint.fromLocal): a template point would land in every room built alike (the ring's
     * garden and the tree's). Null for a plan tile outside one's own house. Client thread.
     */
    private WorldPoint markerPoint(int point)
    {
        if (!HousePlan.contains(Tiles.x(point), Tiles.y(point)))
        {
            // In an instance copying a place: the template's tile, which HD World Markers finds in the instance.
            WorldView scene = client.getTopLevelWorldView();
            int[] template = scene != null && scene.isInstance()
                ? InstanceCopies.template(Tiles.x(point), Tiles.y(point), Tiles.z(point)) : null;
            return template != null ? new WorldPoint(template[0], template[1], template[2]) : worldPoint(point);
        }
        WorldView view = client.getTopLevelWorldView();
        LocalPoint local = view == null || !house.own() ? null : yourHouse.local(view, point);
        return local == null ? null : WorldPoint.fromLocal(view, local.getX(), local.getY(), Tiles.z(point));
    }

    /** The tiles' look, read once for each message. Client thread. */
    private int tileBorder;
    private int tileFill;
    private int tileWidth;
    static final Color END = new Color(255, 90, 70);

    final Client client;
    private final HdMapReforgedConfig config;
    final ConfigManager configManager;
    /** The planted spirit trees grown for this account; read near their patches. */
    final PlantedSpiritTrees planted = new PlantedSpiritTrees();
    private final OverlayManager overlayManager;
    private final EventBus eventBus;
    private final ChatMessageManager chatMessageManager;
    private final HouseTracker house = new HouseTracker();
    /** Where the player came from into the Rune essence mine or the Shipyard, whose exits lead back there. */
    private final ReturnTracker returns = new ReturnTracker();
    private final RouteHouse yourHouse = new RouteHouse(this, house);
    // Nothing on the game's own world map: projecting a long route there every frame is too heavy.
    private final List<Overlay> overlays = List.of(new GameOverlay(), new MinimapOverlay());
    private final MapView.Overlay mapOverlay = this::paintMap;
    private final MapView.MenuContributor menu = this::contribute;

    private volatile ExecutorService searches;
    private volatile ExecutorService background;
    private volatile MapScreen[] screens = new MapScreen[0];
    private volatile RouteController controller;
    private volatile RouteSource source;
    private volatile Pathfinder walker;
    void repaint()
    {
        forScreens(screen -> screen.view().repaint());
    }

    void forScreens(Consumer<MapScreen> action)
    {
        for (MapScreen screen : screens)
        {
            action.accept(screen);
        }
    }
    private volatile Map<Integer, Integer> ports = Collections.emptyMap();
    private volatile int[] shipwrights = new int[0];

    void setIcons(List<MapData.Icon> icons)
    {
        shipwrights = shipwrights(icons);
        seaSpots = seaSpots(icons);
    }

    static int[] shipwrights(List<MapData.Icon> icons)
    {
        return tiles(icons, "Shipwright");
    }

    /** The game's icons on the water (the Barracuda Trials' starts), which are sailed to. */
    static int[] seaSpots(List<MapData.Icon> icons)
    {
        return tiles(icons, "Barracuda Trial");
    }

    /** Where the game's icons of a key on its map stand, on the ground floor. */
    private static int[] tiles(List<MapData.Icon> icons, String kind)
    {
        return icons.stream().filter(icon -> kind.equalsIgnoreCase(icon.kind) && icon.location.getPlane() == 0)
            .mapToInt(icon -> pack(icon.location)).distinct().toArray();
    }
    /** The game's icons on the water, as tiles. */
    private volatile int[] seaSpots = new int[0];
    volatile PlayerState state = PlayerState.UNKNOWN;
    /** A tile, a sea block, -1 in the house, -2 unknown. Written on the client thread. */
    private volatile int start = -2;
    private volatile Route shown;
    private boolean bringUp;
    private Boolean pandemonium;
    private int ticks;
    private int lastQuest = -QUEST_TICKS;

    private final PluginManager pluginManager;
    private volatile Consumer<IntFunction<JComponent>> sidebar = section -> { };
    private volatile Consumer<WorldPoint> sidebarFocus = point -> { };
    private volatile Runnable sidebarTours = () -> { };
    final ClientThread clientThread;
    /** The target handed to Shortest Path, or null. */
    private volatile WorldPoint handedOver;
    private volatile WorldPoint lastLocation;
    /** Whether the current route was asked for by another plugin. */
    private volatile boolean fromPlugin;

    private boolean shortestPathPlanner()
    {
        return config.routePlanner() == HdMapReforgedConfig.RoutePlanner.SHORTEST_PATH;
    }

    private boolean shortestPathOn()
    {
        return pluginOn(ShortestPathBridge.PLUGIN_NAME);
    }

    private boolean pluginOn(String name)
    {
        for (Plugin plugin : pluginManager.getPlugins())
        {
            if (name.equals(plugin.getName()))
            {
                return pluginManager.isPluginEnabled(plugin);
            }
        }
        return false;
    }

    /** Client thread. */
    private boolean warned;

    /** Both planners on is not meant to be: said once after a start, and again when either is switched on. */
    private void warnBoth(boolean again)
    {
        clientThread.invokeLater(() -> {
            warned &= !again;
            if (warned || client.getGameState() != GameState.LOGGED_IN || shortestPathPlanner() || !shortestPathOn())
            {
                return;
            }
            warned = true;
            chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
                .value("Reimagined World Map: its route planner is on, and so is the Shortest Path plugin. The map "
                    + "draws both routes; pick one (Reimagined World Map settings, Route planner).").build());
        });
    }

    @Subscribe
    public void onPluginChanged(PluginChanged event)
    {
        if (event.isLoaded() && ShortestPathBridge.PLUGIN_NAME.equals(event.getPlugin().getName()))
        {
            warnBoth(true);
        }
    }

    /** Whether HD World Markers draws the ground tiles now (checked every 10 ticks). */
    private volatile boolean hdTiles;
    private boolean hdHome;
    private Route[] sentLegs;
    private int lastHdTilesCheck = Integer.MIN_VALUE / 2;
    /** Volatile: a change of the route's look clears it on Swing, the client thread sends and sets it. */
    private volatile Route sentAhead;
    /** The fades as last sent ({@link #fadeSteps}). */
    private long sentFade = 1;
    /**
     * How far from the player route tiles go to HD World Markers: as far as our own overlay draws, beyond any
     * scene, also an HD renderer's expanded one; it draws what lies in the scene, the nearest 1000 first.
     */
    static final int MARKER_REACH = Constants.EXTENDED_SCENE_SIZE;
    /** The scene's base when tiles were last sent (one's house moves with it). Client thread. */
    private long sentBase;
    /** Whether tiles were sent since the last clear. */
    private boolean sentTiles;
    private int sentFrom = -1;

    /** Shift + right-click on the ground. RuneLite entries; nothing is sent to the game. */
    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        if (shortestPathPlanner() || !client.isKeyPressed(KeyCode.KC_SHIFT))
        {
            return;
        }
        boolean walk = Arrays.stream(event.getMenuEntries()).anyMatch(e -> e.getType() == MenuAction.WALK);
        WorldView view = client.getTopLevelWorldView();
        Tile tile = view == null ? null : view.getSelectedSceneTile();
        if (!walk || tile == null)
        {
            return;
        }
        LocalPoint at = tile.getLocalLocation();
        WorldPoint point = yourHouse.plan != null && house.own() ? worldPoint(yourHouse.planNode(at, view.getPlane()))
            : WorldPoint.fromLocalInstance(client, at);
        if (point == null)
        {
            return;
        }
        RouteController c = controller;
        if (c != null && c.target() >= 0)
        {
            menuEntry("Clear route", this::clearRoute);
        }
        menuEntry("Route here", () -> routeTo(point));
    }

    private void menuEntry(String option, Runnable action)
    {
        client.getMenu().createMenuEntry(1)
            .setOption(option)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(e -> SwingUtilities.invokeLater(action));
    }

    /** HD World Markers does not animate what it was sent: it gets fading tiles again at each of this many steps. */
    static final int FADE_STEPS = 4;

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        if (hdTiles && fadeSteps(fading, appearing, System.currentTimeMillis()) != sentFade)
        {
            updateHdTiles(sentFrom);
        }
    }

    /** Which step each fade is at (one done counts as none): a new value means a visible change. */
    static long fadeSteps(Map<Integer, Long> fading, Map<Integer, Long> appearing, long now)
    {
        long steps = 1;
        for (long since : fading.values())
        {
            long step = (now - since) * FADE_STEPS / FADE_MS;
            steps = step < FADE_STEPS ? steps * 31 + step : steps;
        }
        for (long since : appearing.values())
        {
            long step = (now - since) * FADE_STEPS / FADE_IN_MS;
            steps = step < FADE_STEPS ? steps * 31 + step + FADE_STEPS : steps;
        }
        return steps;
    }

    /** HD World Markers dropped every plugin's tiles (logout, world hop, lost connection, profile change): send again. */
    void tileMarkersCleared()
    {
        clientThread.invokeLater(() -> {
            sentAhead = null;
            sentLegs = null;
            sentFrom = -1;
            sentTiles = false;
            lastHdTilesCheck = Integer.MIN_VALUE / 2;
        });
    }

    private void updateHdTiles(int player)
    {
        boolean home = house.own();
        if (ticks - lastHdTilesCheck >= 10 || home != hdHome)
        {
            hdHome = home;
            lastHdTilesCheck = ticks;
            // Also in one's house: its tiles go as the scene's own instance tiles (markerPoint).
            boolean on = config.routeHdTiles() && config.routeInGame() && pluginOn(TileMarkersBridge.PLUGIN_NAME);
            if (!on && hdTiles)
            {
                eventBus.post(TileMarkersBridge.clear());
                sentAhead = null;
                sentTiles = false;
            }
            hdTiles = on;
        }
        if (!hdTiles)
        {
            return;
        }
        Route route = shown;
        // Moved far, or the position became known or unknown; not every tick while it stays unknown (the house).
        boolean moved = player != sentFrom && (player < 0 || sentFrom < 0 || Tiles.distance(player, sentFrom) > 16);
        // The scene loaded again (a teleport in, building mode): the house's instance tiles lie elsewhere now.
        WorldView scene = client.getTopLevelWorldView();
        long base = scene == null ? 0 : (long) scene.getBaseX() << 32 | scene.getBaseY() & 0xffffffffL;
        moved |= home && base != sentBase;
        sentBase = base;
        Running running = tour;
        Route[] legs = running == null ? null : running.legs;
        long now = System.currentTimeMillis();
        long fade = fadeSteps(fading, appearing, now);
        if (route == sentAhead && legs == sentLegs && !moved && fade == sentFade)
        {
            return;
        }
        sentFade = fade;
        sentAhead = route;
        sentLegs = legs;
        sentFrom = player;
        List<Map.Entry<Integer, Route>> later = laterLegs();
        if (route == null && fading.isEmpty() && later.isEmpty())
        {
            if (sentTiles)
            {
                eventBus.post(TileMarkersBridge.clear());
            }
            sentTiles = false;
            return;
        }
        tileBorder = config.routeTileBorder();
        tileFill = config.routeTileFill();
        tileWidth = (int) Math.max(1, Math.round(config.routeTileWidth()));
        List<TileMarkersBridge.Tile> tiles = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        int blocked = RouteText.blockedFrom(route);
        List<Step> steps = route == null ? Collections.<Step>emptyList() : route.steps;
        for (int index = 0; index < steps.size(); index++)
        {
            Step step = steps.get(index);
            Color color = blocked >= 0 && index >= blocked ? CANNOT : current();
            if (step.kind == Kind.WALK)
            {
                for (int point : step.points)
                {
                    if (player < 0 || Tiles.distance(point, player) <= MARKER_REACH)
                    {
                        taken.add(point);
                        tiles.add(hdTile(point, fadeIn(point, now), color));
                    }
                }
            }
            else if (step.isJump() && step.name != null && !Tiles.isSea(step.first()) && step.first() != step.last())
            {
                int at = step.first();
                if (player < 0 || Tiles.distance(at, player) <= MARKER_REACH)
                {
                    WorldPoint marked = markerPoint(at);
                    if (marked != null)
                    {
                        tiles.add(new TileMarkersBridge.Tile(marked,
                            blocked >= 0 && index >= blocked ? CANNOT : jump(), RouteText.said(route, step, false)));
                    }
                }
            }
        }
        // Fading tiles last: HD World Markers takes 1000, and the route near the player matters more.
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            float alpha = fadeAlpha(entry.getValue(), now);
            int point = entry.getKey();
            if (alpha > 0.05f && taken.add(point))
            {
                tiles.add(hdTile(point, alpha, current()));
            }
        }
        // The nearer part wins where they cross.
        for (int i = later.size() - 1; i >= 0; i--)
        {
            Color color = legColor(later.get(i).getKey());
            for (Step step : later.get(i).getValue().steps)
            {
                for (int point : step.kind == Kind.WALK ? step.points : new int[0])
                {
                    if ((player < 0 || Tiles.distance(point, player) <= MARKER_REACH) && taken.add(point))
                    {
                        tiles.add(hdTile(point, 1, color));
                    }
                }
            }
        }
        tiles.removeIf(Objects::isNull);
        eventBus.post(TileMarkersBridge.tiles(tiles));
        sentTiles = true;
    }

    /** The last message we sent Shortest Path, not taken for another plugin's. */
    private volatile PluginMessage sent;

    /**
     * Directions other plugins (Quest Helper, clues) send to Shortest Path: its route is drawn whichever planner is
     * chosen, and ours plans them too (setting). We only ask it to tell its transports, after it took the message:
     * a config in it replaces its overrides.
     */
    @Subscribe(priority = -1)
    public void onPluginMessage(PluginMessage message)
    {
        if (ShortestPathBridge.isTransports(message) && handedOver != null)
        {
            spJumps = ShortestPathBridge.jumps(message);
            walkShortestPath(spJumps, handedOver);
            SwingUtilities.invokeLater(() -> showShortestPath(false));
            return;
        }
        boolean path = ShortestPathBridge.isPath(message);
        WorldPoint target = path ? ShortestPathBridge.nearest(ShortestPathBridge.targets(message), lastLocation) : null;
        if (message == sent || (path ? target == null : !ShortestPathBridge.isClear(message)))
        {
            return;
        }
        if (target == null && handedOver != null)
        {
            forgetHandOver();
            SwingUtilities.invokeLater(() -> showShortestPath(false));
        }
        else if (target != null && shortestPathOn())
        {
            forgetHandOver();
            handedOver = target;
            // As every message to it: from the client thread, whichever thread brought this one.
            PluginMessage ask = ShortestPathBridge.path(null, ShortestPathBridge.config(message));
            clientThread.invokeLater(() -> eventBus.post(sent = ask));
            SwingUtilities.invokeLater(() -> showShortestPath(false));
        }
        if (shortestPathPlanner() || !config.routeFollowPlugins())
        {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            RouteController c = controller;
            if (c != null && target != null)
            {
                fromPlugin = true;
                routeAvoid.clear();
                c.setTarget(pack(target));
            }
            else if (c != null && fromPlugin)
            {
                fromPlugin = false;
                c.clear();
            }
        });
    }

    /** As the planner, Shortest Path's route has the card; else it is only drawn, under ours. */
    private void showShortestPath(boolean up)
    {
        if (shortestPathPlanner())
        {
            showHandedOver(handedOver == null ? null : this::handedOverPanel, up);
        }
        else
        {
            repaint();
        }
    }

    private volatile List<ShortestPathBridge.Jump> spJumps = List.of();
    /** Its walks, looked for here on foot between the points it told (it tells no tiles); per its message. */
    private volatile List<int[]> spWalks = List.of();
    private final AtomicInteger spAsked = new AtomicInteger();

    /**
     * From the player to its first transport, between its transports, from the last to its target: walking only (no
     * shortcut or transport of ours), as its own path walks between the transports it takes.
     */
    private void walkShortestPath(List<ShortestPathBridge.Jump> jumps, WorldPoint target)
    {
        int asked = spAsked.incrementAndGet();
        WorldPoint from = lastLocation;
        Pathfinder pf = walker;
        ExecutorService work = background;
        if (from == null || pf == null || work == null)
        {
            return;
        }
        List<WorldPoint> ends = new ArrayList<>(List.of(from));
        jumps.forEach(j -> ends.addAll(List.of(j.from, j.to)));
        ends.add(target);
        work.execute(() -> {
            List<int[]> walks = new ArrayList<>();
            for (int i = 0; i + 1 < ends.size() && asked == spAsked.get(); i += 2)
            {
                RouteRequest request = new RouteRequest(pack(ends.get(i)), pack(ends.get(i + 1)), List.of(), List.of(),
                    1, true, RouteRequest.DEFAULT_NODE_LIMIT);
                try
                {
                    pf.find(request, () -> asked != spAsked.get()).steps.stream().filter(s -> s.kind == Kind.WALK)
                        .forEach(s -> walks.add(s.points));
                }
                catch (RuntimeException e)
                {
                    log.debug("No walk found for Shortest Path's route", e);
                }
            }
            if (asked == spAsked.get())
            {
                spWalks = walks;
                SwingUtilities.invokeLater(this::repaint);
            }
        });
    }

    private void paintHandedOver(Graphics2D g, MapView.Projection p)
    {
        WorldPoint target = handedOver;
        if (target == null)
        {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float[] jump = {9f, 6f};
        // Beside our route (both planners on): white and dashed, to tell the two apart.
        boolean beside = !shortestPathPlanner();
        Color walked = beside ? Color.WHITE : SP_WALK;
        // Its teleports and transports as it told them, and the walks between them as found here on foot.
        for (int[] walk : spWalks)
        {
            shadowed(g, RouteText.path(p, walk, false), 2.5f, beside ? new float[]{6f, 5f} : null, walked);
        }
        for (ShortestPathBridge.Jump j : spJumps)
        {
            line(g, p, j.from, j.to, jump, beside ? Color.WHITE : new Color(180, 120, 255));
        }
        if (p.shows(target))
        {
            double x = RouteText.x(p, pack(target));
            double y = RouteText.y(p, pack(target));
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(circle(x, y, 7));
            g.setColor(walked);
            g.fill(circle(x, y, 5));
        }
    }

    private static void line(Graphics2D g, MapView.Projection p, WorldPoint a, WorldPoint b, float[] dash, Color color)
    {
        if (p.shows(a) && p.shows(b))
        {
            shadowed(g, new Line2D.Double(RouteText.x(p, pack(a)), RouteText.y(p, pack(a)), RouteText.x(p, pack(b)),
                RouteText.y(p, pack(b))), 2.5f, dash, color);
        }
    }

    /** Drawn over a dark line two pixels wider. */
    private static void shadowed(Graphics2D g, Shape shape, float width, float[] dash, Color color)
    {
        g.setStroke(new BasicStroke(width + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.setColor(SHADOW);
        g.draw(shape);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
        g.setColor(color);
        g.draw(shape);
    }

    private JComponent handedOverPanel(int width)
    {
        JPanel panel = column();
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        WorldPoint target = handedOver;
        if (target == null)
        {
            return panel;
        }
        Running running = tour;
        if (running != null)
        {
            panel.add(tourHeader(running, width));
        }
        panel.add(label("Route by Shortest Path", width, Font.BOLD, Color.WHITE));
        boolean on = shortestPathOn();
        panel.add(label(on ? "Planned by Shortest Path, which draws it in the game. On this map: the teleports and "
            + "transports it takes, and the walks between them as found here on foot."
            : "The Shortest Path plugin is not on. Install or switch it on, or set Route planner to "
                + "Reimagined World Map.",
            width, Font.PLAIN, on ? ColorScheme.LIGHT_GRAY_COLOR : NOTE));
        int number = 1;
        for (ShortestPathBridge.Jump j : spJumps)
        {
            panel.add(label(number++ + ". " + j.name, width, Font.PLAIN, new Color(200, 170, 255)));
        }
        panel.add(button("Clear path", this::handOverClear));
        return panel;
    }

    private void handOver(WorldPoint target)
    {
        forgetHandOver();
        handedOver = target;
        // Shortest Path reads the player's position when a message arrives: client thread.
        PluginMessage path = ShortestPathBridge.path(target);
        clientThread.invokeLater(() -> eventBus.post(sent = path));
        showShortestPath(true);
    }

    /** Shortest Path plans it as the planner (true), or also when both are on: drawn under ours, to compare. */
    private boolean toShortestPath(WorldPoint target)
    {
        boolean planner = shortestPathPlanner();
        if (planner || shortestPathOn())
        {
            handOver(target);
        }
        return planner;
    }

    private void showHandedOver(IntFunction<JComponent> panel, boolean up)
    {
        forScreens(screen -> screen.showRoute(panel, up)); // repaints
        sidebar.accept(panel);
    }

    private void handOverClear()
    {
        forgetHandOver();
        clientThread.invokeLater(() -> eventBus.post(sent = ShortestPathBridge.clear()));
        showShortestPath(false);
    }

    private void forgetHandOver()
    {
        handedOver = null;
        spJumps = List.of();
        spWalks = List.of();
        spAsked.incrementAndGet();
    }

    void start(MapScreen... on)
    {
        int generation = loads.incrementAndGet();
        screens = on;
        // At the lowest priority a busy client starved searches for seconds.
        searches = daemon("Reimagined World Map route", Thread.NORM_PRIORITY - 1);
        background = daemon("Reimagined World Map route extras", Thread.MIN_PRIORITY);
        for (MapScreen screen : on)
        {
            screen.view().addOverlay(mapOverlay);
            screen.view().addMenuContributor(menu);
            screen.setRouter(this::routeTo);
            screen.setStopAdder(this::addStop);
            screen.setFastest(this::lookAtFastest);
            screen.setTours(tourActions);
        }
        overlays.forEach(overlayManager::add);
        eventBus.register(this);
        eventBus.register(yourHouse);
        // The map version's route data, when it came before this start.
        loadPlanner();
        // Started while logged in: this account's house, as at login.
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && generation == loads.get())
            {
                yourHouse.loadHouse();
            }
        });
        warnBoth(false);
    }

    /**
     * Loads the planner from the map version's route.zip (the map server's, {@link RouteFiles}), again for each new one,
     * so the route data is fixed without a plugin update.
     */
    void loadPlanner()
    {
        int generation = loads.get();
        ExecutorService on = searches;
        if (on == null || !RouteFiles.available())
        {
            return;
        }
        on.execute(() -> {
            try
            {
                CollisionMap map = CollisionMap.load();
                SeaMap sea = SeaMap.build(map);
                Pathfinder pathfinder = new Pathfinder(map, sea);
                Map<Integer, Integer> loadedPorts = Ports.load();
                SwingUtilities.invokeLater(() -> {
                    if (generation != loads.get() || searches == null)
                    {
                        return; // stopped (maybe restarted) while loading
                    }
                    ports = loadedPorts;
                    yourHouse.setCollision(map);
                    source = new RouteSource(map, sea, pathfinder.shortcutPassages(), pathfinder::pocket);
                    for (MapScreen screen : screens)
                    {
                        // Entering a dungeon fits the map to its walkable area.
                        screen.view().setAreaBounds(p -> WalkableArea.bounds(map, p.getX(), p.getY(), p.getPlane(),
                            AREA_LIMIT));
                        // A click on ground drawn on another floor than it is walked on asks for that floor.
                        screen.view().setGround(p -> map.walkable(p.getX(), p.getY(), p.getPlane()));
                    }
                    walker = pathfinder;
                    controller = new RouteController(searches, SwingUtilities::invokeLater, pathfinder,
                        target -> request(start, target, false),
                        c -> changed(), System::currentTimeMillis);
                });
            }
            catch (Exception e)
            {
                log.warn("Could not load the route data", e);
            }
        });
    }

    private static ExecutorService daemon(String name, int priority)
    {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        });
    }

    /** Counts starts and stops, so a load finishing after a stop is dropped. */
    private final AtomicInteger loads = new AtomicInteger();

    /** Where the house's picture is kept per account, and the thread that reads and writes it. */
    void setHouseFiles(Filepath folder, Executor io)
    {
        yourHouse.setHouseFiles(folder, io);
    }

    void stop()
    {
        loads.incrementAndGet();
        eventBus.unregister(this);
        eventBus.unregister(yourHouse);
        overlays.forEach(overlayManager::remove);
        fadeTimer.stop();
        fading.clear();
        appearing.clear();
        if (hdTiles)
        {
            hdTiles = false;
            clientThread.invokeLater(() -> eventBus.post(TileMarkersBridge.clear()));
        }
        for (MapScreen screen : screens)
        {
            screen.view().removeOverlay(mapOverlay);
            screen.view().removeMenuContributor(menu);
            screen.showRoute(null, false);
            screen.view().setAreaBounds(p -> null); // holds the collision map
            screen.view().setGround(null);
        }
        sidebar.accept(null);
        sidebar = section -> { };
        sidebarFocus = point -> { };
        sidebarTours = () -> { };
        sidebarNow = null;
        RouteController running = controller;
        if (running != null)
        {
            running.clear();
        }
        controller = null;
        for (ExecutorService pool : new ExecutorService[]{searches, background})
        {
            if (pool != null)
            {
                pool.shutdownNow();
            }
        }
        searches = null;
        background = null;
        screens = new MapScreen[0];
        shown = null;
        source = null;
        walker = null;
        yourHouse.setCollision(null);
        ports = Collections.emptyMap();
        // Client thread state: reset there, before any start's own work there.
        clientThread.invokeLater(() -> {
            yourHouse.forget();
            lastLocation = null;
            start = -2;
            state = PlayerState.UNKNOWN;
            sentAhead = null;
            sentLegs = null;
            sentFrom = -1;
            sentFade = 1;
            sentTiles = false;
            lastHdTilesCheck = Integer.MIN_VALUE / 2;
            warned = false;
        });
        // Work still on the extras thread finds it is not wanted.
        tour = null;
        orders.incrementAndGet();
        if (handedOver != null)
        {
            // Else Shortest Path keeps drawing it, with no Clear path here after a restart.
            clientThread.invokeLater(() -> eventBus.post(ShortestPathBridge.clear()));
        }
        forgetHandOver();
        fastestAsked.incrementAndGet();
        whyAsked.incrementAndGet();
        why = null;
        whyFor = null;
        whyTarget = -1;
        explaining = false;
        tipAsked.incrementAndGet();
        tip = null;
        tipShown = false;
        tipFor = null;
        tipTarget = -1;
        ahead = null;
        aheadOf = null;
        aheadWalked = null;
        routeAvoid.clear();
        aheadWalkedOf = null;
        lastAheadSteps = -1;
        lastAheadPoints = -1;
        bringUp = false;
        fromPlugin = false;
    }

    // ---- client thread ----

    /** In one's house, its portal outside: where "nearest" counts from. */
    WorldPoint outside()
    {
        int exit = house.exit();
        return exit < 0 ? null : worldPoint(exit);
    }

    private WorldPoint nearFrom(int node)
    {
        return node < 0 ? null : HousePlan.contains(Tiles.x(node), Tiles.y(node)) ? outside() : worldPoint(node);
    }

    WorldPoint home()
    {
        int at = yourHouse.inHouse();
        int portal = at >= 0 ? at : !house.inside() ? -1 : house.exit() >= 0 ? house.exit() : house.portal();
        return portal < 0 ? null : worldPoint(portal);
    }

    void tick(WorldPoint location)
    {
        ticks++;
        lastLocation = location;
        Player player = client.getLocalPlayer();
        if (player == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        WorldView view = player.getWorldView();
        boolean onBoat = view != null && !view.isTopLevel();
        boolean instance = client.getTopLevelWorldView() != null && client.getTopLevelWorldView().isInstance();
        int node = location == null ? -1 : pack(location);
        // By a planted spirit tree's patch: whether a grown tree stands there now, kept for this account.
        if (location != null && planted.update(location.getRegionID(), client::getVarbitValue))
        {
            configManager.setRSProfileConfiguration(HdMapReforgedConfig.GROUP, PlantedSpiritTrees.KEY, planted.text());
            SwingUtilities.invokeLater(this::settingsChanged);
        }
        house.update(node, instance, client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION),
            client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1);
        returns.update(node);
        if (house.inside())
        {
            yourHouse.placeHouse(client.getTopLevelWorldView());
        }
        if (house.takeScan())
        {
            yourHouse.scanHouse();
        }
        if (house.takeChanged())
        {
            yourHouse.writeHouse(house.features());
        }
        int from = location == null ? -2 : house.inside() ? yourHouse.inHouse()
            : onBoat ? Tiles.seaAt(location.getX(), location.getY()) : node;
        start = from;
        // Not in other instances (their tiles are copies); in one's own house its plan tells where one is, in an instance
        // copying a place on the map (instance_copies.tsv) that place.
        boolean replans = !instance || house.inside() && from >= 0
            || from >= 0 && InstanceCopies.isPlace(Tiles.x(from), Tiles.y(from));
        updateHdTiles(from);
        // At once also on coming into (or leaving) the Rune essence mine or the Shipyard: their exits lead back.
        if (ticks % STATE_TICKS == 0 || state == PlayerState.UNKNOWN || returns.exit() != state.returnExit)
        {
            state = capture();
        }
        SwingUtilities.invokeLater(() -> {
            RouteController c = controller;
            if (c != null)
            {
                Running running = tour;
                if (running != null && running.target >= 0 && (Tiles.z(from) == Tiles.z(running.target)
                    && Tiles.distance(from, running.target) <= RouteController.ARRIVED
                    || c.target() == running.target && c.arrived(from)))
                {
                    nextStop(from);
                    return;
                }
                if (config.routeClearOnArrival() && c.arrived(from))
                {
                    fadeOut(walkedAhead(ahead));
                    c.clear();
                    return;
                }
                c.playerMoved(from >= 0 ? from : -1, replans);
                updateAhead(c, from);
            }
        });
    }

    private volatile Route ahead;
    /** The sidebar's "Now" box. Swing thread. */
    private JComponent sidebarNow;
    private int lastAheadSteps = -1;
    private int lastAheadPoints = -1;

    /** Passed tiles fade at once: a step or two behind the player, not a trail. */
    static final long FADE_MS = 600;
    private final ConcurrentHashMap<Integer, Long> fading = new ConcurrentHashMap<>();
    private final Timer fadeTimer = new Timer(40, e -> fadeStep());

    private final ConcurrentHashMap<Integer, Long> appearing = new ConcurrentHashMap<>();
    static final long FADE_IN_MS = 400;

    private float fadeIn(int point, long now)
    {
        Long since = appearing.get(point);
        return since == null ? 1f : Math.max(0.05f, Math.min(1f, (now - since) / (float) FADE_IN_MS));
    }

    private void fadeInTiles(Set<Integer> tiles)
    {
        if (tiles.isEmpty() || tiles.size() > 1000)
        {
            return;
        }
        long t = System.currentTimeMillis();
        for (int point : tiles)
        {
            // Fading out a moment ago: it comes back from where it was.
            Long out = fading.remove(point);
            float was = out == null ? 0f : fadeAlpha(out, t);
            appearing.put(point, t - (long) (was * FADE_IN_MS));
        }
        fadeTimer.start(); // no-op while running
    }

    static float fadeAlpha(long since, long now)
    {
        long elapsed = now - since;
        return elapsed <= 0 ? 1f : Math.max(0f, 1f - elapsed / (float) FADE_MS);
    }

    private void fadeStep()
    {
        long now = System.currentTimeMillis();
        // Tiles fade all the while a route is walked: a map paints again only round the dots it shows, not whole.
        for (MapScreen screen : screens)
        {
            Rectangle dots = screen.view().isShowing() ? fadingDots(screen.view().projection(), fading.keySet()) : null;
            if (dots != null)
            {
                screen.view().repaint(dots);
            }
        }
        fading.values().removeIf(since -> now - since >= FADE_MS);
        appearing.values().removeIf(since -> now - since >= FADE_IN_MS);
        if (fading.isEmpty() && appearing.isEmpty())
        {
            fadeTimer.stop();
        }
    }

    /** Where a map shows these passed tiles' dots (appearing tiles fade in the game only), or null. */
    static Rectangle fadingDots(MapView.Projection p, Set<Integer> tiles)
    {
        int r = (int) Math.ceil(dotRadius(p)) + 2;
        Rectangle dots = null;
        for (int point : tiles)
        {
            if (RouteText.visible(p, point))
            {
                Rectangle dot = new Rectangle((int) RouteText.x(p, point) - r, (int) RouteText.y(p, point) - r, 2 * r, 2 * r);
                dots = dots == null ? dot : dots.union(dot);
            }
        }
        return dots;
    }

    private static double dotRadius(MapView.Projection p)
    {
        return Math.max(1.5, Math.pow(2, p.zoom()) * 0.35);
    }

    private static void eachWalked(Route route, IntConsumer tile)
    {
        for (Step step : route.steps)
        {
            if (step.kind == Kind.WALK)
            {
                for (int point : step.points)
                {
                    tile.accept(point);
                }
            }
        }
    }

    private static Set<Integer> walked(Route route)
    {
        Set<Integer> tiles = new HashSet<>();
        if (route != null)
        {
            eachWalked(route, tiles::add);
        }
        return tiles;
    }

    private static Set<Integer> minus(Set<Integer> a, Set<Integer> b)
    {
        Set<Integer> left = new HashSet<>(a);
        left.removeAll(b);
        return left;
    }

    private void fadeOut(Set<Integer> tiles)
    {
        long t = System.currentTimeMillis();
        if (tiles.isEmpty() || tiles.size() > 1000)
        {
            return;
        }
        for (int point : tiles)
        {
            fading.putIfAbsent(point, t);
        }
        fadeTimer.start();
    }

    private Route aheadOf;
    private Route aheadWalkedOf;
    private Set<Integer> aheadWalked;

    /** {@link #walked}, cached: each tick asks for the same route again. */
    private Set<Integer> walkedAhead(Route route)
    {
        if (route != aheadWalkedOf || aheadWalked == null)
        {
            aheadWalkedOf = route;
            aheadWalked = Collections.unmodifiableSet(walked(route));
        }
        return aheadWalked;
    }

    private static long jumps(Route route)
    {
        return route.steps.stream().filter(Step::isJump).count();
    }

    private void updateAhead(RouteController c, int player)
    {
        Route route = effective(c);
        Route now = route == null ? null : route.ahead(player);
        Route before = ahead;
        if (route != null && route == aheadOf && before != null && now != null && now.steps.size() == before.steps.size()
            && (now.steps.isEmpty() || now.steps.get(0).points.length == before.steps.get(0).points.length))
        {
            now = before; // the same cut as last tick
        }
        Set<Integer> walkedBefore = walkedAhead(before);
        Set<Integer> walkedNow = now == before ? walkedBefore : walked(now);
        if (route != null && route == aheadOf && before != null && now != null && player >= 0 && !Tiles.isSea(player)
            && !now.steps.isEmpty() && jumps(now) >= jumps(before) && Tiles.distance(player, now.steps.get(0).first()) > 2)
        {
            // Off the way: the route stays as it was, nothing passed (walking away beside it is not walking it) and
            // nothing back, until the player is on it again or a new search's route fades in. A teleport or
            // transport just taken is passed, wherever it put the player.
            return;
        }
        aheadOf = route;
        if (before != null && before != now)
        {
            fadeOut(minus(walkedBefore, walkedNow));
            fadeInTiles(minus(walkedNow, walkedBefore));
        }
        ahead = now;
        aheadWalkedOf = now;
        // Not wrapped again: on Java 11 the wrappers would nest every tick until the stack overflows.
        aheadWalked = walkedNow == walkedBefore ? walkedBefore : Collections.unmodifiableSet(walkedNow);
        shown = config.routeInGame() ? now : null;
        int steps = now == null ? -1 : now.steps.size();
        int points = now == null || now.steps.isEmpty() ? -1 : now.steps.get(0).points.length;
        if (steps != lastAheadSteps && lastAheadSteps >= 0 && steps >= 0)
        {
            showSteps(c, false);
        }
        else if (points != lastAheadPoints && steps >= 0)
        {
            // The sidebar's "Now" counts down the tiles left of this walk: only its two texts change.
            JComponent box = sidebarNow;
            if (box != null && box.getParent() != null && !now.steps.isEmpty())
            {
                updateNow(box, now);
            }
            else
            {
                sidebar.accept(width -> steps(c, width, sidebarFocus, true));
            }
        }
        if (steps != lastAheadSteps || points != lastAheadPoints)
        {
            lastAheadSteps = steps;
            lastAheadPoints = points;
            repaint();
        }
    }

    PlayerState capture()
    {
        Map<Integer, Long> carried = new HashMap<>();
        Set<Integer> unlimited = new HashSet<>();
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        boolean pouch = inventory != null && Arrays.stream(inventory.getItems()).anyMatch(i -> RUNE_POUCHES.contains(i.getId()));
        if (inventory != null)
        {
            count(inventory, carried);
        }
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        if (worn != null)
        {
            count(worn, carried);
            for (EquipmentInventorySlot slot : new EquipmentInventorySlot[]{EquipmentInventorySlot.WEAPON,
                EquipmentInventorySlot.SHIELD})
            {
                Item item = worn.getItem(slot.getSlotIdx());
                if (item != null && item.getId() >= 0)
                {
                    unlimited.addAll(ItemSnapshot.runesFromWeapon(client.getItemDefinition(item.getId()).getName()));
                }
            }
        }
        if (pouch)
        {
            EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
            for (int i = 0; i < POUCH_TYPES.length; i++)
            {
                int type = client.getVarbitValue(POUCH_TYPES[i]);
                int amount = client.getVarbitValue(POUCH_AMOUNTS[i]);
                int id = type > 0 && runes != null ? runes.getIntValue(type) : -1;
                if (id > 0 && amount > 0)
                {
                    carried.merge(id, (long) amount, Long::sum);
                }
            }
        }
        if (pandemonium == null || ticks - lastQuest >= QUEST_TICKS)
        {
            lastQuest = ticks;
            pandemonium = Quest.PANDEMONIUM.getState(client) == QuestState.FINISHED;
        }
        // Where each boat lies and its teleport focus; boats at one port count once, with the best focus there.
        Map<Integer, Integer> boats = new LinkedHashMap<>();
        for (int i = 0; i < BOAT_PORTS.length; i++)
        {
            Integer tile = ports.get(client.getVarbitValue(BOAT_PORTS[i]));
            if (tile != null)
            {
                boats.merge(tile, client.getVarbitValue(BOAT_FOCUS[i]), Math::max);
            }
        }
        return new PlayerState(ItemSnapshot.of(carried, unlimited), client.getRealSkillLevel(Skill.SAILING),
            pandemonium, boats.keySet().stream().mapToInt(Integer::intValue).toArray(), config.routeRunning(),
            house.inside(), house.inside() ? house.exit() : house.portal(), house.own(), house.features(),
            client.getVarbitValue(VarbitID.POH_TELE_TOGGLE) == 0)
            .withReturn(returns.exit()).withBoatFocus(boats.values().stream().mapToInt(Integer::intValue).toArray());
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        PlayerState before = state;
        state = capture();
        // A tip shows what to bring: once something is picked up (or put away), the route is looked for again, so
        // bringing it takes the quicker way at once. Not more than once in a while: an inventory changes often.
        long now = System.currentTimeMillis();
        if (tipShown && !state.items.sameAs(before.items) && now - lastItemReroute >= ITEM_REROUTE_MS)
        {
            lastItemReroute = now;
            SwingUtilities.invokeLater(this::settingsChanged);
        }
    }

    /** At most one search again for a change of what is carried this often. */
    static final long ITEM_REROUTE_MS = 2_000;
    /** Client thread. */
    private long lastItemReroute;
    /** Whether a tip is shown now (set on the Swing thread, read on the client thread). */
    private volatile boolean tipShown;

    private static void count(ItemContainer container, Map<Integer, Long> into)
    {
        for (Item item : container.getItems())
        {
            if (item.getId() >= 0)
            {
                into.merge(item.getId(), (long) item.getQuantity(), Long::sum);
            }
        }
    }

    /** The house's part is {@link RouteHouse#onGameStateChanged}. */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            pandemonium = null;
            warnBoth(false);
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            start = -2;
            state = PlayerState.UNKNOWN;
        }
    }

    private String get(String key)
    {
        return configManager.getConfiguration(HdMapReforgedConfig.GROUP, key);
    }

    private void set(String key, Object value)
    {
        configManager.setConfiguration(HdMapReforgedConfig.GROUP, key, value);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        String key = event.getKey();
        if (!HdMapReforgedConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }
        if (!key.startsWith("route") || HOUSE_KEY.equals(key) || "routePlannerChosen".equals(key))
        {
            return;
        }
        if ("routePlanner".equals(key))
        {
            warnBoth(true);
            // The other planner's route would stay with no way to clear it. Our own hand-over (the first route
            // choosing Shortest Path) is kept.
            SwingUtilities.invokeLater(() -> {
                RouteController c = controller;
                stopTour();
                if (c != null && c.target() >= 0)
                {
                    c.clear();
                }
                if (handedOver != null && !shortestPathPlanner())
                {
                    handOverClear();
                }
                if (handedOver != null)
                {
                    showHandedOver(this::handedOverPanel, false);
                }
                else
                {
                    changed();
                }
            });
            return;
        }
        if (LOOK_KEYS.contains(key))
        {
            sentAhead = null;
        }
        SwingUtilities.invokeLater(LOOK_KEYS.contains(key) ? this::changed : this::settingsChanged);
    }

    void settingsChanged()
    {
        RouteController c = controller;
        if (c != null)
        {
            c.settingsChanged();
        }
    }

    private void toast(String text)
    {
        forScreens(screen -> screen.showToast(text));
    }

    // ---- Swing ----

    /** With {@code anything}, every requirement counts as met. Swing or the extras thread. */
    private RouteRequest request(int begin, int target, boolean anything)
    {
        return request(begin, target, anything, false);
    }

    /** With {@code everyItem}, the player's levels and quests but any item: what bringing something would give. */
    private RouteRequest request(int begin, int target, boolean anything, boolean everyItem)
    {
        Running r = tour;
        return request(begin, target, anything, everyItem, r == null ? List.of() : r.avoid);
    }

    /** Also without {@code leftOut}: what a custom route leaves out. */
    private RouteRequest request(int begin, int target, boolean anything, boolean everyItem, List<String> leftOut)
    {
        RouteSource from = source;
        MapScreen[] on = screens;
        if (from == null || begin < -1 || on.length == 0)
        {
            return null;
        }
        MapView view = on[0].view();
        RouteSource.Options options = new RouteSource.Options(config.routeTeleports(), config.routeSailing(),
            shipwrights, anything || config.routeIgnoreLevels(),
            anything || everyItem || config.routeIgnoreItems(), avoided(leftOut), anything ? RouteSource.Saving.NONE
                : new RouteSource.Saving(config.routeSaveTeleport(), config.routeSaveShortcut(),
                    config.routeSaveTransport(), config.routeSaveCanoe(), config.routeSaveCarpet(), config.routeSaveShip()),
            anything, config.routeAvoidWilderness(), config.routeSailToLand());
        nameItems(view.pois());
        from.itemNames(itemNames);
        from.plantedSpiritTrees(planted.grown());
        return from.request(begin, target, view.pois(), view.unlocks(), state, options, RouteRequest.DEFAULT_NODE_LIMIT);
    }

    /** The game's item names, so a step says what it lacks by name; set by the plugin. */
    private volatile ItemNames itemNames;
    /** The place list whose items were named last. */
    private volatile List<Poi> named;

    void itemNames(ItemNames names)
    {
        itemNames = names;
    }

    /** Asks the game once per place list for the names of the items its teleports and transports need. */
    private void nameItems(List<Poi> pois)
    {
        ItemNames names = itemNames;
        if (names == null || pois == named)
        {
            return;
        }
        named = pois;
        Set<Integer> ids = new HashSet<>();
        for (Poi poi : Poi.flatten(pois))
        {
            for (Poi member : poi.members())
            {
                ids.addAll(Requirements.itemIds(member.needs.items));
            }
        }
        names.resolve(ids, () -> { });
    }

    /** On the first map right-click: Shortest Path becomes the planner if it is on and nothing was chosen. */
    private void choosePlannerOnce()
    {
        if (get("routePlannerChosen") != null)
        {
            return;
        }
        set("routePlannerChosen", true);
        if (get("routePlanner") == null && shortestPathOn())
        {
            set("routePlanner", HdMapReforgedConfig.RoutePlanner.SHORTEST_PATH);
        }
    }

    /** Of a search's places, this many nearest as the crow flies are planned to, for the fastest. */
    static final int FASTEST_OF = 8;
    /** Which look for the fastest is current; an older one stops. */
    private final AtomicInteger fastestAsked = new AtomicInteger();

    /**
     * Swing thread: of these places the map shows the one the planner gets to soonest (with the player's teleports and
     * transport), without starting a route; the nearest as the crow flies when no route can be planned now.
     */
    void lookAtFastest(List<WorldPoint> places)
    {
        int from = start;
        ExecutorService pool = background;
        Pathfinder pathfinder = walker;
        List<WorldPoint> nearest = new ArrayList<>(new LinkedHashSet<>(places));
        if (from >= 0)
        {
            nearest.sort(Comparator.comparingLong(p -> {
                long dx = p.getX() - Tiles.x(from);
                long dy = p.getY() - Tiles.y(from);
                return dx * dx + dy * dy + (p.getPlane() == Tiles.z(from) ? 0 : 100_000_000L);
            }));
        }
        if (nearest.isEmpty())
        {
            return;
        }
        List<WorldPoint> candidates = nearest.subList(0, Math.min(FASTEST_OF, nearest.size()));
        if (from < 0 || pool == null || pathfinder == null || shortestPathPlanner() || candidates.size() == 1)
        {
            lookAt(candidates.get(0));
            return;
        }
        int asked = fastestAsked.incrementAndGet();
        toast("Finding the fastest…");
        try
        {
            pool.execute(() -> {
                WorldPoint best = candidates.get(0);
                int bestTime = Integer.MAX_VALUE;
                int bestCost = Integer.MAX_VALUE;
                for (WorldPoint p : candidates)
                {
                    if (fastestAsked.get() != asked)
                    {
                        return;
                    }
                    RouteRequest request = request(from, atSea(p) ? Tiles.seaAt(p.getX(), p.getY()) : pack(p), false);
                    Route route;
                    try
                    {
                        // Nearest as the crow flies first; a place that cannot beat the best so far is given up on
                        // early (the search's cost weighs teleports a little heavier than their time).
                        route = request == null ? null
                            : pathfinder.find(request, () -> fastestAsked.get() != asked, bestCost);
                    }
                    catch (Throwable e)
                    {
                        log.warn("Could not plan to {}", p, e);
                        continue;
                    }
                    if (route != null && route.outcome == Outcome.FOUND && route.time() < bestTime)
                    {
                        best = p;
                        bestTime = route.time();
                        bestCost = route.cost;
                    }
                }
                WorldPoint chosen = best;
                SwingUtilities.invokeLater(() -> {
                    if (fastestAsked.get() == asked)
                    {
                        lookAt(chosen);
                    }
                });
            });
        }
        catch (RejectedExecutionException e)
        {
            lookAt(candidates.get(0));
        }
    }

    /** Swing thread: the maps show a search result's place, as a click on it in the list does. */
    private void lookAt(WorldPoint place)
    {
        forScreens(screen -> screen.lookAtFound(place));
    }

    /** Swing thread; nothing while the data loads. */
    void routeTo(WorldPoint point)
    {
        stopTour();
        if (toShortestPath(point))
        {
            return;
        }
        RouteController now = controller;
        if (now != null)
        {
            newRoute();
            now.setTarget(atSea(point) ? Tiles.seaAt(point.getX(), point.getY()) : pack(point));
        }
    }

    /**
     * A spot of the game on the water (a Barracuda Trial's start, salvage) lies here, or beside it (a click on the
     * icon): it is sailed to.
     */
    private boolean atSea(WorldPoint point)
    {
        if (point.getPlane() != 0)
        {
            return false;
        }
        int at = pack(point);
        if (Arrays.stream(seaSpots).anyMatch(spot -> Tiles.distance(spot, at) <= 2))
        {
            return true;
        }
        if (screens.length == 0)
        {
            return false;
        }
        for (Poi poi : screens[0].view().pois())
        {
            if (poi.atSea() && poi.location.getPlane() == 0 && poi.location.distanceTo2D(point) <= 2)
            {
                return true;
            }
        }
        return false;
    }

    /** The user's own route: shown at once, with nothing avoided yet. */
    private void newRoute()
    {
        bringUp = true;
        fromPlugin = false;
        routeAvoid.clear();
    }

    private void contribute(JPopupMenu popup, WorldPoint point)
    {
        // Before the entries are made, so they name the planner that will be used.
        choosePlannerOnce();
        RouteController c = controller;
        popup.addSeparator();
        item(popup, "Add to custom route", () -> addStop(Tour.Stop.place(placeName(point), point)))
            .setToolTipText("Adds this spot as a stop of \"" + editingName() + "\"");
        item(popup, "Custom routes...", () -> forScreens(MapScreen::openTours))
            .setToolTipText("Your own routes with several stops: order them, let the planner find the fastest order, run");
        item(popup, "Your house...", yourHouse::openHouse)
            .setToolTipText("What your house has for routes (this account), and its plan once you have been in it");
        if (shortestPathPlanner())
        {
            item(popup, "Path to here (Shortest Path)", () -> routeTo(point));
        }
        else
        {
            item(popup, c == null ? "Path to here (loading…)" : "Path to here", () -> routeTo(point)).setEnabled(c != null);
        }
        if (handedOver != null || c != null && c.target() >= 0)
        {
            item(popup, tour != null ? "Stop custom route" : "Clear path", this::clearRoute);
        }
    }

    /** Max tiles of a walkable area to count as one dungeon to fit the map to. */
    static final int AREA_LIMIT = 60_000;

    // ---- custom routes ----

    static final String TOURS_KEY = "customRoutes";
    static final String EDITING_KEY = "customRouteEditing";

    @RequiredArgsConstructor
    private static final class Running
    {
        final Tour tour;
        volatile int index;
        volatile int target = -1;
        /** Each stop's tile, fixed at the start (a kind: the one nearest the stop before); -1 none. */
        int[] points = new int[0];
        /** The way to each stop from the one before, planned at the start; null until found. */
        volatile Route[] legs = new Route[0];
        /** Stops skipped one after another, having no place. */
        int skipped;
        /** The ways the route leaves out, as saved; a copy for the other threads. */
        volatile List<String> avoid = List.of();
        /** Counts the plans of its parts: an older plan still running finds it is not wanted. */
        volatile int plan;
    }

    /** Set on Swing; read on the client and extras threads too. */
    private volatile Running tour;

    /** The running custom route's name, or null. */
    String runningTour()
    {
        Running r = tour;
        return r == null ? null : r.tour.name;
    }

    /**
     * Swing thread: a way (or every one of its kind) the running custom route leaves out from now on, saved with the
     * route; the way to the current stop is looked for again, and the later parts planned again.
     */
    void avoidInRoute(String name)
    {
        Running r = tour;
        if (r == null)
        {
            return;
        }
        List<Tour> all = tours();
        Tour saved = all.stream().filter(t -> t.name.equals(r.tour.name)).findFirst().orElse(null);
        if (saved == null || saved.avoid.size() >= Tour.MAX_AVOIDED)
        {
            toast(saved == null ? "\"" + r.tour.name + "\" is no longer saved" : "\"" + r.tour.name + "\" leaves out "
                + Tour.MAX_AVOIDED + " ways already, as many as a route can");
            return;
        }
        saved.avoid.add(name);
        r.avoid = new ArrayList<>(saved.avoid);
        saveTours(all, editingName());
        toast("\"" + r.tour.name + "\" now leaves out " + Tour.describe(name).toLowerCase(Locale.ROOT));
        settingsChanged();
        r.legs = new Route[r.points.length];
        r.plan++;
        planLegs(r);
        refreshTours();
    }

    private List<Tour> tours()
    {
        return Tour.decode(get(TOURS_KEY));
    }

    private void saveTours(List<Tour> tours, String editing)
    {
        set(TOURS_KEY, Tour.encode(tours));
        set(EDITING_KEY, editing);
    }

    /** The route stops are added to: the last one edited, else the first, else a new "My route". */
    private String editingName()
    {
        String editing = get(EDITING_KEY);
        List<Tour> tours = tours();
        return tours.stream().anyMatch(t -> t.name.equals(editing)) ? editing
            : tours.isEmpty() ? "My route" : tours.get(0).name;
    }

    void addStop(Tour.Stop stop)
    {
        List<Tour> tours = tours();
        String name = editingName();
        Tour target = Tour.named(tours, name);
        if (target.stops.size() >= Tour.MAX_STOPS)
        {
            JOptionPane.showMessageDialog(screens.length > 0 ? screens[0] : null, "\"" + name + "\" has "
                + Tour.MAX_STOPS + " stops, as many as a route can have. Make another route under Custom routes.",
                "Custom routes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        target.stops.add(stop);
        saveTours(tours, name);
        refreshTours();
        toast("Added to \"" + name + "\" (" + target.stops.size() + " stops)");
    }

    private String placeName(WorldPoint point)
    {
        if (screens.length > 0)
        {
            for (Poi poi : screens[0].view().pois())
            {
                if (poi.location.getPlane() == point.getPlane() && poi.location.distanceTo2D(point) <= 2)
                {
                    return poi.name;
                }
            }
            String place = screens[0].placeName(point);
            if (place != null)
            {
                return "Near " + place;
            }
        }
        return "A spot on the map";
    }

    private final TourPanel.Actions tourActions = new TourPanel.Actions()
    {
        @Override
        public List<Tour> tours()
        {
            return RouteFeature.this.tours();
        }

        @Override
        public String editing()
        {
            return editingName();
        }

        @Override
        public void save(List<Tour> tours, String editing)
        {
            saveTours(tours, editing);
            refreshTours();
        }

        @Override
        public void run(Tour tour)
        {
            runTour(tour);
        }

        @Override
        public void fastestOrder(Tour tour, boolean everything, Runnable done)
        {
            RouteFeature.this.fastestOrder(tour, everything, done);
        }

        @Override
        public void say(String text)
        {
            toast(text);
        }

        @Override
        public void cancelOrder()
        {
            orders.incrementAndGet();
        }

        @Override
        public String running()
        {
            Running r = RouteFeature.this.tour;
            return r == null ? null : r.tour.name;
        }

        @Override
        public void stop()
        {
            clearRoute();
        }
    };

    private void refreshTours()
    {
        forScreens(MapScreen::refreshTours);
        sidebarTours.run();
    }

    void runTour(Tour chosen)
    {
        if (chosen.stops.isEmpty())
        {
            return;
        }
        // A stop right after the same one is skipped.
        Running running = new Running(chosen.withoutRepeats());
        running.avoid = new ArrayList<>(chosen.avoid);
        tour = running;
        List<WorldPoint> at = resolve(running.tour.stops, nearFrom(start));
        running.points = at.stream().mapToInt(p -> p == null ? -1 : pack(p)).toArray();
        running.legs = new Route[at.size()];
        goToStop(start);
        planLegs(running);
        refreshTours();
    }

    /** Plans every leg in the background, so the whole route is drawn at once. */
    private void planLegs(Running running)
    {
        ExecutorService work = background;
        Pathfinder pf = walker;
        if (work == null || pf == null || shortestPathPlanner())
        {
            return;
        }
        int plan = running.plan;
        BooleanSupplier stale = () -> tour != running || running.plan != plan || background == null;
        work.execute(() -> {
            for (int i = 1; i < running.points.length; i++)
            {
                if (stale.getAsBoolean())
                {
                    return;
                }
                int from = running.points[i - 1];
                int to = running.points[i];
                RouteRequest request = from < 0 || to < 0 ? null : request(from, to, false);
                Route leg;
                try
                {
                    leg = request == null ? null : pf.find(request, stale);
                }
                catch (Throwable e)
                {
                    log.warn("Could not plan a part of the custom route", e);
                    return;
                }
                if (leg != null && leg.outcome != Outcome.CANCELLED)
                {
                    int at = i;
                    SwingUtilities.invokeLater(() -> {
                        if (running.plan != plan)
                        {
                            return;
                        }
                        Route[] legs = running.legs.clone();
                        legs[at] = leg;
                        running.legs = legs;
                        repaint();
                    });
                }
            }
        });
    }

    TourPanel.Actions sidebarTours(Runnable refresh)
    {
        sidebarTours = refresh;
        return tourActions;
    }

    /** Also shows the steps in the sidebar; a step clicked there goes to {@code focus}. */
    void setSidebar(Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus)
    {
        sidebar = show;
        sidebarFocus = focus;
        changed();
    }

    private Color legColor(int leg)
    {
        return leg <= 0 ? walk() : LEG_COLORS[(leg - 1) % LEG_COLORS.length];
    }

    private Color current()
    {
        Running running = tour;
        return running == null ? walk() : legColor(running.index);
    }

    /** The custom route's parts after the current one, last first (drawn under the nearer ones). */
    private List<Map.Entry<Integer, Route>> laterLegs()
    {
        Running running = tour;
        List<Map.Entry<Integer, Route>> later = new ArrayList<>();
        if (running == null)
        {
            return later;
        }
        Route[] legs = running.legs;
        for (int i = legs.length - 1; i > running.index; i--)
        {
            if (legs[i] != null && !legs[i].steps.isEmpty())
            {
                later.add(Map.entry(i, legs[i]));
            }
        }
        return later;
    }

    /** Stops a custom route and clears the route, also Shortest Path's. */
    private void clearRoute()
    {
        RouteController c = controller;
        stopTour();
        if (c != null)
        {
            c.clear();
        }
        if (handedOver != null)
        {
            handOverClear();
        }
    }

    private void stopTour()
    {
        if (tour != null)
        {
            tour = null;
            refreshTours();
        }
    }

    private void nextStop(int at)
    {
        Running running = tour;
        RouteController c = controller;
        if (running == null || c == null)
        {
            return;
        }
        running.index++;
        if (running.index >= running.tour.stops.size() && running.index > 1 && loops(running))
        {
            running.index = 0;
        }
        else if (running.index >= running.tour.stops.size())
        {
            fadeOut(walkedAhead(ahead));
            clearRoute();
            toast("\"" + running.tour.name + "\" done");
            return;
        }
        goToStop(at);
    }

    /** As saved, so ticking Loop counts for the route already running. */
    private boolean loops(Running running)
    {
        return tours().stream().anyMatch(t -> t.loop && t.name.equals(running.tour.name));
    }

    /** Routes to the current stop; a kind becomes the one nearest {@code from}. */
    private void goToStop(int from)
    {
        Running running = tour;
        RouteController c = controller;
        if (running == null || c == null)
        {
            return;
        }
        int planned = running.index < running.points.length ? running.points[running.index] : -1;
        WorldPoint to = planned >= 0 ? worldPoint(planned)
            : resolve(running.tour.stops.get(running.index), nearFrom(from));
        if (to == null)
        {
            // A kind with no place yet (icons not loaded): skipped. A loop of only such stops ends.
            if (++running.skipped > running.tour.stops.size())
            {
                clearRoute();
                return;
            }
            nextStop(from);
            return;
        }
        running.skipped = 0;
        running.target = pack(to);
        if (toShortestPath(to))
        {
            return;
        }
        newRoute();
        Route[] legs = running.legs;
        Route leg = running.index > 0 && running.index < legs.length ? legs[running.index] : null;
        if (leg != null && leg.outcome == Outcome.FOUND)
        {
            c.show(running.target, leg);
            return;
        }
        c.setTarget(running.target);
    }

    /** Each stop's place; a kind becomes the one nearest the stop before (or {@code near}); null when none. */
    private List<WorldPoint> resolve(List<Tour.Stop> stops, WorldPoint near)
    {
        List<WorldPoint> points = new ArrayList<>();
        for (Tour.Stop stop : stops)
        {
            WorldPoint p = resolve(stop, near);
            points.add(p);
            near = p != null ? p : near;
        }
        return points;
    }

    private WorldPoint resolve(Tour.Stop stop, WorldPoint near)
    {
        if (!stop.isKind())
        {
            return stop.point;
        }
        return Tour.nearest(screens.length == 0 ? null : screens[0].kind(stop.kind), near);
    }

    /** "▶ My route: stop 2 of 5", its stops, Next stop and Stop route, above a running custom route's steps. */
    private JComponent tourHeader(Running running, int width)
    {
        JPanel header = column();
        List<Tour.Stop> stops = running.tour.stops;
        header.add(label("\u25B6 " + running.tour.name + (loops(running) ? " (loop)" : "") + ": stop "
            + (running.index + 1) + " of " + stops.size(), width,
            Font.BOLD, legColor(running.index)));
        for (int i = 0; i < stops.size(); i++)
        {
            header.add(label((i + 1) + ". " + stops.get(i).name, width, i == running.index ? Font.BOLD : Font.PLAIN,
                i < running.index ? ColorScheme.MEDIUM_GRAY_COLOR : legColor(i)));
        }
        JPanel buttons = panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(button(running.index + 1 < stops.size() || stops.size() > 1 && loops(running) ? "Next stop" : "Finish",
            () -> nextStop(start)));
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(button("Stop route", this::clearRoute));
        header.add(buttons);
        header.add(Box.createVerticalStrut(6));
        return header;
    }

    /**
     * Orders the stops fastest from the player, by routes between every two (in the background), and saves it unless
     * the stops changed meanwhile. {@code done} always runs on Swing.
     */
    private void fastestOrder(Tour chosen, boolean everything, Runnable done)
    {
        ExecutorService work = background;
        Pathfinder pf = walker;
        int from = start;
        if (work == null || pf == null)
        {
            done.run();
            return;
        }
        String name = chosen.name;
        List<Tour.Stop> stops = Collections.unmodifiableList(new ArrayList<>(chosen.stops));
        int order = orders.incrementAndGet();
        WorldPoint me = from >= 0 ? worldPoint(from) : null;
        List<WorldPoint> points = new ArrayList<>();
        points.add(me);
        points.addAll(resolve(stops, me));
        List<String> leftOut = new ArrayList<>(chosen.avoid);
        toast("Working out the fastest order of " + stops.size() + " stops"
            + (everything ? " with everything in the game…" : "…"));
        BooleanSupplier cancelled = () -> background == null || orders.get() != order;
        try
        {
            work.execute(() -> {
                List<Integer> sorted = null;
                try
                {
                    int n = points.size();
                    // Each trip's cost with a home or minigame teleport (cast once in the route, then its cooldown),
                    // and without: the order takes such a teleport on one trip at most.
                    long[][] once = new long[n][n];
                    long[][] cost = new long[n][n];
                    // The same ways from any spot on land (the stops and the player are tiles): asked for once.
                    RouteRequest ways = request(0, 0, everything, false, leftOut);
                    List<String> without = new ArrayList<>(leftOut);
                    without.add(RouteSource.ONCE_ONLY);
                    RouteRequest plainWays = request(0, 0, everything, false, without);
                    // Stop by stop: the searches to one stop share their heuristic, and one that tried everything tells
                    // where else that stop is out of reach. None back to the player: an order never returns there.
                    for (int b = 1; b < n && !cancelled.getAsBoolean(); b++)
                    {
                        List<Route> outOfReach = new ArrayList<>();
                        List<Route> plainOutOfReach = new ArrayList<>();
                        for (int a = 0; a < n; a++)
                        {
                            Route trip = a == b ? null : between(pf, ways, points.get(a), points.get(b), outOfReach, cancelled);
                            once[a][b] = a == b ? 0 : trip == null ? -1 : trip.cost;
                            // Without the teleport only where the trip took one.
                            cost[a][b] = trip == null || trip.steps.stream().noneMatch(RouteSource::onceOnly) ? once[a][b]
                                : tripCost(between(pf, plainWays, points.get(a), points.get(b), plainOutOfReach, cancelled));
                        }
                    }
                    sorted = cancelled.getAsBoolean() ? null : Tour.fastestOrder(cost, once);
                }
                catch (Throwable e)
                {
                    log.warn("Could not work out the fastest order", e);
                }
                finally
                {
                    List<Integer> result = sorted;
                    SwingUtilities.invokeLater(() -> {
                        try
                        {
                            if (result != null && orders.get() == order)
                            {
                                applyOrder(name, stops, result);
                            }
                            else if (orders.get() != order)
                            {
                                // Another panel's order or a cancel: said, not dropped without a word.
                                toast("Stopped working out the fastest order of \"" + name + "\"");
                            }
                        }
                        finally
                        {
                            done.run();
                        }
                    });
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            done.run();
        }
    }

    /** Saves the order if the route's stops are unchanged. */
    private void applyOrder(String name, List<Tour.Stop> stops, List<Integer> order)
    {
        List<Tour> all = tours();
        for (int i = 0; i < all.size(); i++)
        {
            Tour now = all.get(i);
            if (!now.name.equals(name))
            {
                continue;
            }
            if (!sameStops(now.stops, stops))
            {
                toast("\"" + name + "\" changed meanwhile: its order was left as it is");
                return;
            }
            List<Tour.Stop> sorted = new ArrayList<>();
            for (int at : order)
            {
                sorted.add(now.stops.get(at - 1));
            }
            all.set(i, new Tour(name, sorted));
            // The route edited now stays so, also when another was chosen meanwhile.
            saveTours(all, editingName());
            return;
        }
        toast("\"" + name + "\" was renamed or removed meanwhile: its order was not saved");
    }

    private static boolean sameStops(List<Tour.Stop> a, List<Tour.Stop> b)
    {
        return a.size() == b.size()
            && IntStream.range(0, a.size()).allMatch(i -> a.get(i).same(b.get(i)) && a.get(i).name.equals(b.get(i).name));
    }

    /** Which fastest-order run is current; any other stops and keeps nothing. */
    private final AtomicInteger orders = new AtomicInteger();

    private static long tripCost(Route trip)
    {
        return trip == null ? -1 : trip.cost;
    }

    /**
     * The trip found, or null when there is no way, also where an earlier search to {@code b} that tried everything
     * got to. From nowhere known (the player's place unknown) every first stop is as good: a trip costing nothing.
     */
    private static Route between(Pathfinder pf, RouteRequest ways, WorldPoint a, WorldPoint b, List<Route> outOfReach,
        BooleanSupplier cancelled)
    {
        if (b == null || ways == null)
        {
            return null;
        }
        if (a == null)
        {
            return new Route(Outcome.FOUND, Collections.emptyList(), 0, pack(b), pack(b), false, false, 0);
        }
        int from = pf.startNode(pack(a));
        if (outOfReach.stream().anyMatch(proved -> proved.reached(from)))
        {
            return null;
        }
        Route route = pf.find(ways.between(pack(a), pack(b)), cancelled);
        if (route.exhausted && route.outcome != Outcome.FOUND)
        {
            outOfReach.add(route);
        }
        return route.outcome == Outcome.FOUND ? route : null;
    }

    /** Even with every requirement met there is no way: the data lacks a passage. */
    static final String NO_WAY = "No way found, even with everything.";

    /** Swing thread: for an unreachable target, the way with every requirement met. */
    private Route why;
    private volatile Route whyFor;
    private int whyTarget = -1;
    private boolean explaining;
    /** Which look for the way anyone could go is current; any other stops and keeps nothing. */
    private final AtomicInteger whyAsked = new AtomicInteger();

    /** Looks in the background for what is missing. Swing thread. */
    private void explain(RouteController c, Route route)
    {
        if (route == whyFor)
        {
            return;
        }
        boolean nearest = route != null && route.outcome == Outcome.NEAREST;
        // Searched again only because the player moved: the way anyone could go stays as found (or as being found),
        // not looked for by flooding the world once more.
        boolean keep = nearest && c.onlyMoved() && c.target() == whyTarget && (why != null || explaining);
        whyFor = route;
        if (keep)
        {
            return;
        }
        int asked = whyAsked.incrementAndGet();
        explaining = false;
        if (c.target() != whyTarget)
        {
            why = null;
            whyTarget = c.target();
        }
        ExecutorService pool = background;
        Pathfinder pathfinder = walker;
        // Whenever the way does not arrive (also at the search limit), so the whole route shows with what is missing.
        if (!nearest || pool == null || pathfinder == null || config.routeIgnoreLevels() && config.routeIgnoreItems())
        {
            why = null;
            return;
        }
        RouteRequest request = request(start, c.target(), true);
        if (request == null)
        {
            whyFor = null; // retry once the start is known
            return;
        }
        explaining = true;
        if (!TileCache.execute(pool, () -> explained(c, request, pathfinder, asked)))
        {
            explaining = false;
        }
    }

    /** Extras thread; always hands back to Swing, so nothing waits on it. */
    private void explained(RouteController c, RouteRequest request, Pathfinder pathfinder, int asked)
    {
        Route result = null;
        try
        {
            result = pathfinder.find(request, () -> whyAsked.get() != asked);
        }
        catch (Throwable e)
        {
            log.warn("Could not look for the way anyone could go", e);
        }
        finally
        {
            Route found = result;
            SwingUtilities.invokeLater(() -> {
                if (whyAsked.get() == asked)
                {
                    why = found;
                    explaining = false;
                    changed();
                }
            });
        }
    }

    /** A tip is given when bringing items saves at least this many half ticks (15 seconds) ... */
    static final int TIP_MIN_SAVED = 50;
    /** ... and this share of the route's time. */
    static final int TIP_MIN_PERCENT = 15;

    /** Swing thread: what to bring to get there notably sooner, for {@link #tipFor}; null when nothing. */
    private Tip tip;
    private Route tipFor;
    private int tipTarget = -1;
    /** Which look for a tip is current; any other stops and keeps nothing. */
    private final AtomicInteger tipAsked = new AtomicInteger();

    /** Looks in the background once per target whether carrying something would make the way notably shorter. */
    private void lookForTip(RouteController c, Route route)
    {
        if (route == tipFor)
        {
            return;
        }
        tipFor = route;
        // Searched again only because the player moved: the tip found for this target stays.
        if (c.onlyMoved() && c.target() == tipTarget)
        {
            return;
        }
        int asked = tipAsked.incrementAndGet();
        tipTarget = c.target();
        tip = null;
        tipShown = false;
        ExecutorService pool = background;
        Pathfinder pathfinder = walker;
        if (route == null || route.outcome != Outcome.FOUND || pool == null || pathfinder == null || !config.routeTips()
            || config.routeIgnoreItems())
        {
            return;
        }
        int from = start;
        int target = c.target();
        TileCache.execute(pool, () -> tipFound(c, route, request(from, target, false, true), pathfinder, asked));
    }

    /** Extras thread; always hands back to Swing. */
    private void tipFound(RouteController c, Route yours, RouteRequest request, Pathfinder pathfinder, int asked)
    {
        Route result = null;
        try
        {
            result = request == null ? null : pathfinder.find(request, () -> tipAsked.get() != asked);
        }
        catch (Throwable e)
        {
            log.warn("Could not look for a quicker way with other items", e);
        }
        Route found = result;
        Tip made = tip(yours, found);
        SwingUtilities.invokeLater(() -> {
            if (tipAsked.get() != asked)
            {
                return;
            }
            tip = made;
            tipShown = made != null;
            List<Integer> unnamed = made == null ? Collections.emptyList() : made.unnamed();
            ItemNames names = itemNames;
            if (!unnamed.isEmpty() && names != null)
            {
                // Asked of the game only now: once named, the tip is looked for again to say them.
                // resolve() calls back on the Swing thread.
                names.resolve(unnamed, () -> {
                    if (tipAsked.get() == asked && controller == c)
                    {
                        tipFor = null;
                        tipTarget = -1;
                        changed();
                    }
                });
            }
            changed();
        });
    }

    /** What to bring for a quicker way: each thing with the step that needs it, and how much sooner. */
    @RequiredArgsConstructor
    static final class Tip
    {
        private static final Pattern UNNAMED = Pattern.compile("\\bItem (\\d+)\\b");

        /** "3200 × Coins", with the step it is for: "Charter ship: Port Tyras". */
        final List<String[]> bring;
        /** "About 2 minutes sooner". */
        final String sooner;

        String text()
        {
            return "Tip: bring " + bring.stream().map(b -> b[0] + " (" + b[1] + ")").collect(Collectors.joining("; "))
                + ": " + sooner.substring(0, 1).toLowerCase(Locale.ROOT) + sooner.substring(1);
        }

        /** Item ids the game had not named yet ("Item 995"). */
        List<Integer> unnamed()
        {
            List<Integer> ids = new ArrayList<>();
            for (String[] b : bring)
            {
                Matcher m = UNNAMED.matcher(b[0]);
                while (m.find())
                {
                    ids.add(Integer.parseInt(m.group(1)));
                }
            }
            return ids;
        }
    }

    /**
     * What to bring when the way with any item is notably quicker than the player's and some step of it needs what
     * they do not carry; else null.
     */
    static Tip tip(Route yours, Route withItems)
    {
        if (yours == null || withItems == null || yours.outcome != Outcome.FOUND || withItems.outcome != Outcome.FOUND)
        {
            return null;
        }
        int saved = yours.time() - withItems.time();
        if (saved < TIP_MIN_SAVED || saved * 100 < yours.time() * TIP_MIN_PERCENT)
        {
            return null;
        }
        List<String[]> bring = new ArrayList<>();
        for (Step step : withItems.steps)
        {
            if (RouteText.lacks(step))
            {
                bring.add(new String[]{RouteText.lack(step), RouteText.describe(step)});
            }
        }
        if (bring.isEmpty())
        {
            return null;
        }
        return new Tip(bring, RouteText.about((saved + 1) / 2) + " sooner");
    }

    /** The steps that need what the player lacks. Null when nothing to say; empty when nothing nameable. */
    static List<String> blockers(Route why)
    {
        if (why == null || why.outcome == Outcome.CANCELLED)
        {
            return null;
        }
        if (why.outcome != Outcome.FOUND)
        {
            // Only a search that tried everything may say there is no way.
            return why.exhausted ? Collections.singletonList(NO_WAY) : null;
        }
        List<String> lines = new ArrayList<>();
        for (Step step : why.steps)
        {
            if (RouteText.lacks(step))
            {
                lines.add(RouteText.describe(step) + ": needs " + RouteText.lack(step));
            }
        }
        return lines;
    }

    private Route effective(RouteController c)
    {
        Route route = c.route();
        if (unreachable(route))
        {
            return why;
        }
        // Not the way to the nearest spot while the real way is looked for.
        return route != null && route.outcome == Outcome.NEAREST && explaining ? null : route;
    }

    private boolean unreachable(Route route)
    {
        Route way = why;
        return route != null && route.outcome == Outcome.NEAREST && route == whyFor && way != null
            && (way.outcome == Outcome.FOUND || way.outcome == Outcome.NEAREST && closer(way, route));
    }

    private static boolean closer(Route a, Route b)
    {
        if (a.end < 0 || a.steps.isEmpty())
        {
            return false;
        }
        if (b.end < 0 || b.steps.isEmpty())
        {
            return true;
        }
        return distance(a.end, a.target) < distance(b.end, b.target);
    }

    private static int distance(int from, int to)
    {
        return Tiles.distance(from, to) + (Tiles.z(from) == Tiles.z(to) ? 0 : 32);
    }

    private void changed()
    {
        RouteController c = controller;
        if (c != null)
        {
            explain(c, c.route());
            lookForTip(c, c.route());
        }
        Route route = c == null ? null : effective(c);
        Route before = ahead;
        // Cut again only for another route: the same one follows the player each tick (and stays while they are off it).
        if (route != aheadOf)
        {
            ahead = route == null ? null : route.ahead(start);
            aheadOf = route;
            if (before != null && before != ahead)
            {
                // The old way fades out as the new one fades in.
                Set<Integer> was = walkedAhead(before);
                Set<Integer> now = walkedAhead(ahead);
                fadeOut(minus(was, now));
                fadeInTiles(minus(now, was));
            }
        }
        shown = config.routeInGame() ? ahead : null;
        boolean up = bringUp;
        bringUp = false;
        showSteps(c, up);
        repaint();
    }

    private void showSteps(RouteController c, boolean up)
    {
        boolean none = c == null || c.target() < 0;
        forScreens(screen -> screen.showRoute(none ? null : width -> steps(c, width, screen.view()::focus, false),
            !none && up));
        sidebar.accept(none ? null : width -> steps(c, width, sidebarFocus, true));
    }

    /** Steps avoided for the current route only (lower case). */
    private final Set<String> routeAvoid = ConcurrentHashMap.newKeySet();

    List<String> neverUsed()
    {
        List<String> names = new ArrayList<>();
        for (String line : config.routeNeverUse().split("[\\r\\n]+"))
        {
            if (!line.trim().isEmpty())
            {
                names.add(line.trim());
            }
        }
        return names;
    }

    private Set<String> avoided(List<String> leftOut)
    {
        Set<String> all = new HashSet<>(routeAvoid);
        neverUsed().forEach(name -> all.add(name.toLowerCase(Locale.ROOT)));
        leftOut.forEach(name -> all.add(name.toLowerCase(Locale.ROOT)));
        return all;
    }

    void avoid(String name)
    {
        routeAvoid.add(name.toLowerCase(Locale.ROOT));
        settingsChanged();
    }

    void never(String name)
    {
        List<String> names = neverUsed();
        if (!names.contains(name))
        {
            names.add(name);
            set("routeNeverUse", String.join("\n", names));
        }
    }

    void allow(String name)
    {
        List<String> names = neverUsed();
        names.removeIf(n -> n.equalsIgnoreCase(name));
        set("routeNeverUse", String.join("\n", names));
    }

    List<String> blockers()
    {
        return blockers(why);
    }

    boolean unreachable()
    {
        RouteController c = controller;
        return c != null && unreachable(c.route());
    }

    /** With {@code roomy} (the sidebar): first a box with what to do now and next. */
    private JComponent steps(RouteController c, int width, Consumer<WorldPoint> focus, boolean roomy)
    {
        Route route = effective(c);
        String status = route == null && explaining ? "Cannot get there with what you have; looking for the way there…"
            : c.status();
        Route left = ahead;
        int done = route != null && route == aheadOf && left != null ? route.steps.size() - left.steps.size() : 0;
        JComponent panel = panel(route, c.target(), status, width, focus, this::clearRoute, this, current(),
            jump(), done);
        if (roomy && route != null && left != null && !left.steps.isEmpty())
        {
            sidebarNow = nowBox(left, width, current(), jump());
            panel.add(sidebarNow, 1);
        }
        Running running = tour;
        if (running != null)
        {
            panel.add(tourHeader(running, width), 0);
        }
        if (handedOver != null)
        {
            panel.add(label("Dashed white on the map: Shortest Path's route", width, Font.PLAIN,
                ColorScheme.MEDIUM_GRAY_COLOR), 1);
        }
        PlayerState planned = state;
        if (route != null && route.has(Kind.SAIL) && planned != null)
        {
            // So a wrong dock choice can be told apart from a wrong level.
            String text = config.routeIgnoreLevels() ? "Planned ignoring levels"
                : "Planned for Sailing level " + planned.sailingLevel;
            panel.add(label(text, width, Font.PLAIN, ColorScheme.MEDIUM_GRAY_COLOR), 1);
        }
        Tip hint = tip;
        if (hint != null && route != null && route == tipFor)
        {
            // Right under the "Now" box, else first under the headings.
            int at = sidebarNow != null && sidebarNow.getParent() == panel ? panel.getComponentZOrder(sidebarNow) + 1 : 1;
            panel.add(tipBox(hint.bring, hint.sooner, width), Math.min(at, panel.getComponentCount()));
        }
        return panel;
    }

    private void paintMap(Graphics2D g, MapView.Projection p)
    {
        if (p.map() != null && (p.map().id == BaseMap.SURFACE || p.map().id == BaseMap.FULL))
        {
            yourHouse.paintHouse(g, p);
        }
        paintHandedOver(g, p);
        if (shortestPathPlanner())
        {
            return;
        }
        Route route = ahead;
        for (Map.Entry<Integer, Route> leg : laterLegs())
        {
            RouteText.paint(g, p, leg.getValue(), legColor(leg.getKey()), jump());
        }
        if (route == null && fading.isEmpty())
        {
            return;
        }
        if (route != null)
        {
            RouteText.paint(g, p, route, current(), jump());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            int point = entry.getKey();
            float alpha = fadeAlpha(entry.getValue(), now);
            if (alpha > 0 && RouteText.visible(p, point))
            {
                g.setColor(alpha(walk(), alpha * 200));
                g.fill(circle(RouteText.x(p, point), RouteText.y(p, point), dotRadius(p)));
            }
        }
    }

    /** The step to take now: a teleport, or a transport or passage the player stands at; or null. */
    static Step nextAction(Route route, int player)
    {
        for (Step step : route.steps)
        {
            if (step.kind == Kind.WALK)
            {
                if (step.points.length > 3)
                {
                    return null;
                }
                continue;
            }
            if (step.kind == Kind.TELEPORT || step.kind == Kind.HOUSE)
            {
                return step;
            }
            if (step.kind == Kind.SAIL || step.kind == Kind.DISEMBARK)
            {
                return null;
            }
            int at = step.first();
            return player >= 0 && at >= 0 && Tiles.distance(player, at) <= 3 ? step : null;
        }
        return null;
    }

    private static final int OBSTACLE_AHEAD = 5;

    /** What to do now, such as "Chop-down Vines (bring an axe)", or null. */
    static String nextText(Route route, int player)
    {
        for (Step step : route.steps)
        {
            if (step.kind != Kind.WALK)
            {
                break;
            }
            for (Route.Obstacle obstacle : step.obstacles)
            {
                for (int i = 0; i < step.points.length && i <= OBSTACLE_AHEAD; i++)
                {
                    if (step.points[i] == obstacle.at)
                    {
                        return obstacle.text;
                    }
                }
            }
            if (step.points.length > OBSTACLE_AHEAD)
            {
                break;
            }
        }
        Step step = nextAction(route, player);
        return step == null ? null : RouteText.describe(step);
    }

    private void paintNextAction(Graphics2D g, Route route, Player player)
    {
        int at = start;
        Step now = nextAction(route, at);
        // A teleport one walks to (a portal, the box, a fairy ring) is named on it, as HD World Markers names it; over
        // the head only what is cast anywhere, and obstacles on the way.
        Step jump = route.steps.stream().filter(Step::isJump).findFirst().orElse(null);
        boolean onIt = jump != null && jump.kind != Kind.TELEPORT && jump.first() >= 0
            && (jump.kind != Kind.HOUSE || HousePlan.contains(Tiles.x(jump.first()), Tiles.y(jump.first())));
        WorldView view = client.getTopLevelWorldView();
        if (onIt && !hdTiles && view != null)
        {
            tag(g, yourHouse.local(view, jump.first()), RouteText.said(route, jump, house.inside()),
                RouteText.lacks(jump), 0);
        }
        String text = nextText(route, at);
        if (text == null || now != null && now == jump && onIt)
        {
            return;
        }
        if (now != null && text.equals(RouteText.describe(now)))
        {
            // The step itself, not an obstacle on the way: into the house with what to use there.
            text = RouteText.said(route, now, house.inside());
        }
        tag(g, player.getLocalLocation(), text, now != null && RouteText.lacks(now), player.getLogicalHeight() + 60);
    }

    private void tag(Graphics2D g, LocalPoint at, String text, boolean lacks, int height)
    {
        // The font first: the text is centred with it.
        g.setFont(FontManager.getRunescapeSmallFont());
        Point spot = at == null ? null : Perspective.getCanvasTextLocation(client, g, at, text, height);
        if (spot != null)
        {
            g.setColor(Color.BLACK);
            g.drawString(text, spot.getX() + 1, spot.getY() + 1);
            g.setColor(lacks ? CANNOT : jump());
            g.drawString(text, spot.getX(), spot.getY());
        }
    }

    private static final int MINIMAP_ALPHA = 150;

    /** Where the player is for the overlays: in one's house its plan's tile. Client thread. */
    private int near()
    {
        int at = start;
        return house.own() && at >= 0 ? at : pack(client.getLocalPlayer().getWorldLocation());
    }

    /** The colour last made: tiles side by side share it, so a frame does not make a Color for every tile. */
    private static final class Tint
    {
        private Color base;
        private int alpha = -1;
        private Color made;

        Color of(Color c, float a)
        {
            int value = Math.max(0, Math.min(255, Math.round(a)));
            if (c != base || value != alpha)
            {
                base = c;
                alpha = value;
                made = new Color(c.getRed(), c.getGreen(), c.getBlue(), value);
            }
            return made;
        }
    }

    private interface TileSink
    {
        void tile(int point, Color color, float strength);
    }

    /**
     * The ground tiles of the overlays within {@code reach} of {@code me}: later parts of a custom route, the route
     * (fading in), then passed tiles. Asked every frame: far tiles are left out before anything is looked up.
     */
    private void eachTile(Route route, int me, int reach, TileSink sink)
    {
        for (Map.Entry<Integer, Route> leg : laterLegs())
        {
            Color color = legColor(leg.getKey());
            eachWalked(leg.getValue(), point -> {
                if (Tiles.distance(point, me) <= reach)
                {
                    sink.tile(point, color, 1);
                }
            });
        }
        Color normal = current();
        long now = System.currentTimeMillis();
        int blocked = RouteText.blockedFrom(route);
        List<Step> steps = route == null ? List.of() : route.steps;
        for (int index = 0; index < steps.size(); index++)
        {
            Step step = steps.get(index);
            Color walk = blocked >= 0 && index >= blocked ? CANNOT : normal;
            for (int point : step.kind == Kind.WALK ? step.points : new int[0])
            {
                if (Tiles.distance(point, me) <= reach && !fading.containsKey(point))
                {
                    sink.tile(point, walk, fadeIn(point, now));
                }
            }
        }
        for (Map.Entry<Integer, Long> entry : fading.entrySet())
        {
            float alpha = fadeAlpha(entry.getValue(), now);
            if (alpha > 0 && Tiles.distance(entry.getKey(), me) <= reach)
            {
                sink.tile(entry.getKey(), normal, alpha);
            }
        }
    }

    /** Dots on the minimap: above the widgets, or the minimap would cover them. */
    private final class MinimapOverlay extends Overlay
    {
        private final Tint tint = new Tint();

        MinimapOverlay()
        {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_WIDGETS);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            Route route = shown;
            if (route == null && fading.isEmpty())
            {
                return null;
            }
            WorldView view = client.getTopLevelWorldView();
            if (view == null || view.isInstance() && !house.own() || client.getLocalPlayer() == null || !config.routeInGame()
                || !config.routeMinimap())
            {
                return null;
            }
            int plane = view.getPlane();
            // The minimap shows 20 tiles around the player (Perspective.localToMinimap).
            eachTile(route, near(), 20, (point, color, strength) -> {
                LocalPoint local = Tiles.isSea(point) || Tiles.z(point) != plane ? null : yourHouse.local(view, point);
                Point mini = local == null ? null : Perspective.localToMinimap(client, local);
                if (mini != null)
                {
                    g.setColor(tint.of(color, MINIMAP_ALPHA * strength));
                    g.fill(circle(mini.getX(), mini.getY(), 1.25));
                }
            });
            return null;
        }
    }

    private final class GameOverlay extends Overlay
    {
        private final Tint fillTint = new Tint();
        private final Tint borderTint = new Tint();

        GameOverlay()
        {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_SCENE);
        }

        @Override
        public Dimension render(Graphics2D g)
        {
            Route route = shown;
            Player player = client.getLocalPlayer();
            if (!config.routeInGame() || player == null)
            {
                return null;
            }
            WorldView view = client.getTopLevelWorldView();
            boolean instance = view == null || view.isInstance();
            if (route != null && (!instance || house.inside()))
            {
                // Not in other instances (their tiles copy a template); in the house the portal is the next thing.
                paintNextAction(g, route, player);
            }
            // With the route done its tiles still fade away; HD World Markers draws them when it is on.
            if (route == null && fading.isEmpty() || instance && !house.own() || hdTiles)
            {
                return null;
            }
            int plane = view.getPlane();
            Stroke old = g.getStroke();
            int fill = config.routeTileFill();
            int borderAlpha = config.routeTileBorder();
            double tileWidth = config.routeTileWidth();
            boolean border = borderAlpha > 0 && tileWidth > 0;
            g.setStroke(new BasicStroke((float) Math.max(0.5, Math.min(8, tileWidth))));
            // No scene, also an expanded one, reaches further.
            eachTile(route, near(), Constants.EXTENDED_SCENE_SIZE, (point, color, strength) -> {
                LocalPoint local = Tiles.z(point) != plane ? null : yourHouse.local(view, point);
                Polygon tile = local == null ? null : tilePoly(view, local, plane);
                if (tile != null)
                {
                    g.setColor(fillTint.of(color, fill * strength));
                    g.fill(tile);
                    if (border)
                    {
                        g.setColor(borderTint.of(color, borderAlpha * strength));
                        g.draw(tile);
                    }
                }
            });
            g.setStroke(old);
            return null;
        }
    }

    /**
     * Tiles past each edge of the scene an expanded scene loads (the GPU plugins' expanded map loading, in chunks of
     * 8), at most to the extended scene's size; 0 without. Client thread.
     */
    int expandedTiles()
    {
        return Math.min(EXPANDED_EDGE, 8 * Math.max(0, client.getExpandedMapLoading()));
    }

    /** Tiles the extended scene reaches past each edge of the scene. */
    private static final int EXPANDED_EDGE = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

    /**
     * A tile's outline on the canvas, also past the scene's edge in an expanded scene: RuneLite's own takes the ground
     * there as flat, so the corners' heights are read from the extended scene's heights.
     */
    private Polygon tilePoly(WorldView view, LocalPoint local, int plane)
    {
        int sx = local.getSceneX();
        int sy = local.getSceneY();
        if (sx >= 0 && sy >= 0 && sx < view.getSizeX() && sy < view.getSizeY())
        {
            return Perspective.getCanvasTilePoly(client, local);
        }
        int[][][] heights = view.getTileHeights();
        int ex = sx + EXPANDED_EDGE;
        int ey = sy + EXPANDED_EDGE;
        if (heights == null || plane < 0 || plane >= heights.length || ex < 0 || ey < 0
            || ex + 1 >= heights[plane].length || ey + 1 >= heights[plane][ex].length)
        {
            return null;
        }
        int half = Perspective.LOCAL_TILE_SIZE / 2;
        int x = local.getX();
        int y = local.getY();
        Point sw = Perspective.localToCanvas(client, x - half, y - half, heights[plane][ex][ey]);
        Point se = Perspective.localToCanvas(client, x + half, y - half, heights[plane][ex + 1][ey]);
        Point ne = Perspective.localToCanvas(client, x + half, y + half, heights[plane][ex + 1][ey + 1]);
        Point nw = Perspective.localToCanvas(client, x - half, y + half, heights[plane][ex][ey + 1]);
        if (sw == null || se == null || ne == null || nw == null)
        {
            return null;
        }
        Polygon poly = new Polygon();
        poly.addPoint(sw.getX(), sw.getY());
        poly.addPoint(se.getX(), se.getY());
        poly.addPoint(ne.getX(), ne.getY());
        poly.addPoint(nw.getX(), nw.getY());
        return poly;
    }

    static final class RouteText
    {
        /** "Can't yet: 45 Sailing, Lunar Diplomacy". */
        static String cannotYet(Route route)
        {
            Set<String> missing = new LinkedHashSet<>();
            for (Step step : route.steps)
            {
                if (lacks(step))
                {
                    for (String part : lack(step).split(",\\s*"))
                    {
                        if (!part.trim().isEmpty())
                        {
                            missing.add(part.trim());
                        }
                    }
                }
            }
            return missing.isEmpty() ? "Can't yet." : "Can't yet: " + String.join(", ", missing);
        }

        static boolean lacks(Step step)
        {
            return step.detail != null && step.detail.startsWith("You lack");
        }

        /** What a step {@link #lacks}, as "45 Sailing, Lunar Diplomacy". */
        static String lack(Step step)
        {
            return step.detail.replaceFirst("^You lack:?\\s*", "");
        }

        /** The first step the player cannot take yet (drawn red from there on), or -1. */
        static int blockedFrom(Route route)
        {
            for (int i = 0; route != null && i < route.steps.size(); i++)
            {
                if (lacks(route.steps.get(i)))
                {
                    return i;
                }
            }
            return -1;
        }

        private RouteText()
        {
        }

        static String escape(String text)
        {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        static List<String> notes(Route route)
        {
            List<String> notes = new ArrayList<>();
            if (route.outcome == Outcome.NONE)
            {
                notes.add("No route from here.");
            }
            else if (route.outcome == Outcome.NEAREST)
            {
                notes.add(route.exhausted ? "Can't get there." : "Too far to plan: ends as close as it got.");
            }
            else if (route.snapped())
            {
                notes.add("Ends at the nearest tile you can stand on.");
            }
            if (route.throughWilderness && route.outcome == Outcome.FOUND)
            {
                notes.add("Through the Wilderness: no other way gets there.");
            }
            return notes;
        }

        private static final Pattern BRING = Pattern.compile("\\(bring (.+)\\)$");

        /** As "an axe, a machete", or null. */
        static String bring(Route route)
        {
            Set<String> tools = new LinkedHashSet<>();
            for (Step step : route.steps)
            {
                for (Route.Obstacle obstacle : step.obstacles)
                {
                    Matcher m = BRING.matcher(obstacle.text);
                    if (m.find())
                    {
                        tools.add(m.group(1));
                    }
                }
            }
            return tools.isEmpty() ? null : String.join(", ", tools);
        }

        /** Whether a detail is only the caution that it may need something unknown. */
        static boolean unsure(String detail)
        {
            return detail != null && detail.startsWith("May need");
        }

        static String duration(Route route)
        {
            int ticks = (route.time() + 1) / 2;
            return about(ticks) + " (" + ticks + " ticks)";
        }

        static String about(int ticks)
        {
            int seconds = (int) Math.round(ticks * 0.6);
            return "About " + (seconds < 90 ? seconds + " seconds" : Math.round(seconds / 60.0) + " minutes");
        }

        /**
         * The jewellery box's menu in its order, which gives each place its key: 1 to 9, then A to R. Our name for the
         * place, then the box's where it differs; kept as our name to the box's key and name ("burthorpe games room" to
         * "5 Burthorpe").
         */
        private static final Map<String, String> BOX = box("Emir's Arena", "Castle Wars|Castle Wars Arena",
            "Ferox Enclave", "Fortis Colosseum", "Burthorpe Games Room|Burthorpe", "Barbarian Assault|Barbarian Outpost",
            "Corporeal Beast", "Tears of Guthix|Chasm of Tears", "Wintertodt Camp", "Warriors' Guild", "Champions' Guild",
            "Edgeville Monastery", "Ranging Guild", "Fishing Guild", "Mining Guild", "Crafting Guild",
            "Cooking Guild|Cooks' Guild", "Woodcutting Guild", "Farming Guild", "Miscellania", "Grand Exchange",
            "Falador Park", "Dondakan|Dondakan's Rock", "Edgeville", "Karamja", "Draynor Village", "Al-Kharid|Al Kharid");
        /** How a house step from outside joins the way in to what is used inside. */
        private static final String INTO = " → ";
        private static final Pattern LEVEL = Pattern.compile("\\(level (\\d+)\\)");

        private static Map<String, String> box(String... places)
        {
            Map<String, String> keyed = new HashMap<>();
            for (int i = 0; i < places.length; i++)
            {
                String[] names = places[i].split("\\|");
                keyed.put(names[0].toLowerCase(Locale.ROOT), "123456789ABCDEFGHIJKLMNOPQR".charAt(i) + " "
                    + names[names.length - 1]);
            }
            return keyed;
        }

        /**
         * What a house step uses in the house, as the game says it: "Jewellery box 5 Burthorpe" (with the key the box's
         * menu gives it), "Mounted glory Edgeville", "Portal Falador", "Fairy ring BKS", "Obelisk level 27".
         */
        static String inHouse(String name)
        {
            int arrow = name.indexOf(INTO);
            String what = arrow < 0 ? name : name.substring(arrow + INTO.length());
            int colon = what.indexOf(": ");
            String place = what.substring(what.lastIndexOf(": ") + 2);
            if (what.startsWith("Jewellery box: ") || what.startsWith("Mounted glory: "))
            {
                String keyed = BOX.get(place.toLowerCase(Locale.ROOT));
                return what.startsWith("Mounted") ? "Mounted glory " + (keyed == null ? place : keyed.substring(2))
                    : "Jewellery box " + (keyed == null ? place : keyed);
            }
            if (what.startsWith("House obelisk: "))
            {
                Matcher level = LEVEL.matcher(what);
                return level.find() ? "Obelisk level " + level.group(1) : "Obelisk " + place;
            }
            if (what.startsWith("House ") && colon > 0 || what.startsWith("Portal nexus: "))
            {
                // "House fairy ring: BKS", "House spirit tree: …", "House portal: Falador", "Portal nexus: Falador".
                String thing = what.startsWith("House ") ? what.substring("House ".length(), colon) : "portal nexus";
                return Character.toUpperCase(thing.charAt(0)) + thing.substring(1) + " " + place;
            }
            return what;
        }

        /**
         * A step as said in the game, over the head and on its tile: a house step outside the house by the way in and
         * what to use there ("Teleport to House, then Jewellery box 5 Burthorpe"), inside by what to use; a way into the
         * house with the house step after it; else as {@link #describe}.
         */
        static String said(Route route, Step step, boolean inside)
        {
            if (step.kind == Kind.HOUSE && step.name != null)
            {
                int arrow = step.name.indexOf(INTO);
                return arrow < 0 || inside ? inHouse(step.name)
                    : step.name.substring(0, arrow) + ", then " + inHouse(step.name);
            }
            int to = step.last();
            if (!inside && step.isJump() && to >= 0 && HousePlan.contains(Tiles.x(to), Tiles.y(to)))
            {
                // Into the house, then past the walk (and stairs) inside to what is used there.
                for (int i = route.steps.indexOf(step) + 1; i < route.steps.size(); i++)
                {
                    Step next = route.steps.get(i);
                    if (next.kind == Kind.HOUSE && next.name != null)
                    {
                        return describe(step) + ", then " + inHouse(next.name);
                    }
                    if (next.kind != Kind.WALK && next.kind != Kind.STAIRS)
                    {
                        break;
                    }
                }
            }
            return describe(step);
        }

        static String describe(Step step)
        {
            switch (step.kind)
            {
                case WALK:
                {
                    int tiles = step.points.length - 1;
                    return "Walk " + tiles + (tiles == 1 ? " tile" : " tiles")
                        + (step.doors > 0 ? " (" + step.doors + (step.doors == 1 ? " door)" : " doors)") : "");
                }
                case SAIL:
                    return "Sail about " + (step.points.length - 1) * Tiles.CELL + " tiles";
                case STAIRS:
                    return step.name + " to floor " + Tiles.z(step.last());
                default:
                    return step.name != null ? step.name : step.kind.name().toLowerCase(Locale.ROOT);
            }
        }

        private static final Color OUTLINE = new Color(0, 0, 0, 140);

        static void paint(Graphics2D g, MapView.Projection p, Route route, Color walk, Color jump)
        {
            double scale = Math.pow(2, p.zoom());
            float width = (float) Math.max(2.5, Math.min(6, scale / 2));
            Stroke solid = new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            Stroke dashed = new BasicStroke(Math.max(2f, width - 1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1,
                new float[]{7, 6}, 0);
            Stroke outline = new BasicStroke(width + 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
            int blocked = blockedFrom(route);
            for (int index = 0; index < route.steps.size(); index++)
            {
                Step step = route.steps.get(index);
                if (blocked >= 0 && index >= blocked)
                {
                    walk = CANNOT;
                    jump = CANNOT;
                }
                if (step.isJump())
                {
                    float[] at = drawn(p, step.points);
                    int b = 3 * (step.points.length - 1);
                    boolean showA = shown(p, at, 0, false);
                    boolean showB = shown(p, at, b, false);
                    if (showA && showB && step.first() != step.last())
                    {
                        g.setStroke(dashed);
                        g.setColor(color(step, walk, jump));
                        g.draw(new Line2D.Double(p.screenX(at[0]), p.screenY(at[1]), p.screenX(at[b]), p.screenY(at[b + 1])));
                    }
                    g.setColor(jump);
                    if (showA)
                    {
                        g.fill(circle(p.screenX(at[0]), p.screenY(at[1]), 4));
                    }
                    if (showB)
                    {
                        g.fill(circle(p.screenX(at[b]), p.screenY(at[b + 1]), 4));
                    }
                    continue;
                }
                Path2D line = path(p, step.points, false);
                g.setStroke(outline);
                g.setColor(OUTLINE);
                g.draw(line);
                g.setStroke(solid);
                g.setColor(color(step, walk, jump));
                g.draw(line);
            }
            if (route.end >= 0 && visible(p, route.end))
            {
                Ellipse2D end = circle(x(p, route.end), y(p, route.end), 6);
                g.setStroke(new BasicStroke(2f));
                g.setColor(END);
                g.fill(end);
                g.setColor(Color.WHITE);
                g.draw(end);
            }
            if (route.snapped() && visible(p, route.target))
            {
                // The spot asked for, crossed out, with a thin line to where the route ends.
                double tx = x(p, route.target);
                double ty = y(p, route.target);
                g.setStroke(new BasicStroke(2.5f));
                g.setColor(END);
                g.draw(new Line2D.Double(tx - 6, ty - 6, tx + 6, ty + 6));
                g.draw(new Line2D.Double(tx - 6, ty + 6, tx + 6, ty - 6));
                if (route.end >= 0 && visible(p, route.end))
                {
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 4}, 0));
                    g.draw(new Line2D.Double(tx, ty, x(p, route.end), y(p, route.end)));
                }
            }
        }

        /** The points' line where shown, broken where not; {@code anyFloor}: also on other floors than the one drawn. */
        static Path2D path(MapView.Projection p, int[] points, boolean anyFloor)
        {
            Path2D line = new Path2D.Double();
            boolean drawing = false;
            float[] at = drawn(p, points);
            for (int i = 0; i < at.length; i += 3)
            {
                if (!shown(p, at, i, anyFloor))
                {
                    drawing = false;
                }
                else if (drawing)
                {
                    line.lineTo(p.screenX(at[i]), p.screenY(at[i + 1]));
                }
                else
                {
                    line.moveTo(p.screenX(at[i]), p.screenY(at[i + 1]));
                    drawing = true;
                }
            }
            return line;
        }

        /** Where routes' points are drawn on one map, worked out once per points and map (not per point every frame). */
        private static final Map<int[], float[]> DRAWN = new WeakHashMap<>();
        private static BaseMap drawnOn;

        /**
         * Per point: its x and y as drawn ({@code NaN} where this map does not show it) and its floor (-1 at sea: shown on
         * every floor).
         */
        private static synchronized float[] drawn(MapView.Projection p, int[] points)
        {
            if (p.map() != drawnOn)
            {
                DRAWN.clear();
                drawnOn = p.map();
            }
            return DRAWN.computeIfAbsent(points, k -> {
                float[] at = new float[points.length * 3];
                for (int i = 0; i < points.length; i++)
                {
                    int node = points[i];
                    boolean sea = Tiles.isSea(node);
                    WorldPoint game = worldPoint(node);
                    WorldPoint shown = sea ? game : p.shown(game);
                    at[3 * i] = !p.shows(game) ? Float.NaN : sea ? Tiles.x(node) : shown.getX() + 0.5f;
                    at[3 * i + 1] = sea ? Tiles.y(node) : shown.getY() + 0.5f;
                    at[3 * i + 2] = sea ? -1 : shown.getPlane();
                }
                return at;
            });
        }

        private static boolean shown(MapView.Projection p, float[] at, int i, boolean anyFloor)
        {
            return !Float.isNaN(at[i]) && (anyFloor || at[i + 2] < 0 || at[i + 2] == p.plane());
        }

        private static boolean visible(MapView.Projection p, int node)
        {
            WorldPoint game = worldPoint(node);
            if (Tiles.isSea(node))
            {
                return p.shows(game);
            }
            // Where the map draws it: a part drawn elsewhere (the Kalphite Lair) there, on the floor drawn.
            return p.shown(game).getPlane() == p.plane() && p.shows(game);
        }

        static double x(MapView.Projection p, int node)
        {
            return p.screenX(Tiles.isSea(node) ? Tiles.x(node) : p.shown(worldPoint(node)).getX() + 0.5);
        }

        static double y(MapView.Projection p, int node)
        {
            return p.screenY(Tiles.isSea(node) ? Tiles.y(node) : p.shown(worldPoint(node)).getY() + 0.5);
        }
    }
}
