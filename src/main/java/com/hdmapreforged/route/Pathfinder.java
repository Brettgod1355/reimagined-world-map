package com.hdmapreforged.route;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * A* over tiles, sea blocks and jumps ({@link Edge}); costs in half ticks. The heuristic stays a lower bound by folding
 * underground y onto the surface (y − 6400) and bounding far/cheap jumps with a backward search over the jumps alone.
 * Thread-safe: each search keeps its own state; the maps are immutable (one's house is swapped whole).
 */
public final class Pathfinder
{
    /** Half ticks. */
    static final int RUN = 1;
    static final int WALK = 2;
    static final int DOOR = 2;
    /** Vines, jungle or a web: needs a tool, so walking around is better. */
    static final int OBSTACLE = 10;
    static final int SAIL = Tiles.CELL;
    static final int STAIRS = 5;
    /** Through a solid object found by shape: only when no known way is much shorter. */
    static final int THROUGH = 30;
    static final String UNKNOWN_NEEDS = "May need a quest, level or item the map does not know";
    static final int SNAP_RADIUS = 12;
    private static final int LONG_JUMP = 24;
    private static final int BLOCK = 8;
    static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    final CollisionMap map;
    final SeaMap sea;
    /** Every passage the planner knows besides the request's: stairs, entrances, map links and the like. */
    final List<Edge> passages;
    private final List<ShortcutPassage> shortcutPassages;
    final EdgeIndex passagesByOrigin;
    /** The jumps the heuristic must know when a tile costs a half tick (running, sailing) and when it costs one. */
    private final List<Edge> heuristicPassages;
    private final List<Edge> walkingPassages;
    /**
     * Walking steps a gates.tsv row between two neighbouring tiles closes (a guild's door, which the game's collision has
     * as an ordinary door): per origin tile, a bit per direction of {@link #DX}; the row's own edge opens the step to
     * players who meet its needs. Read only after the constructor.
     */
    private final IntMap closedSteps = new IntMap(64);
    /** The heuristic's jump bounds as last worked out, by goal and the request's jumps: re-plans and Fastest order. */
    private final Map<List<Integer>, int[][]> bounds = Collections.synchronizedMap(new LruMap<>(8, BOUNDS_KEPT));
    static final int BOUNDS_KEPT = 4;

    public Pathfinder(CollisionMap map, SeaMap sea)
    {
        this.map = map;
        this.sea = sea;
        List<Edge> edges = new ArrayList<>();
        // Passages only from: the game's map links, hand links citing a source,
        // and cache passages: within SAME_SPOT, or far ones (into a dungeon, 6400 tiles north) that the collision tool
        // paired with a way back at the other end, unless a map link of the game's starts there and leads elsewhere.
        List<Edge> mapLinks = links(map, "/com/hdmapreforged/route/map_link_passages.tsv");
        List<Edge> handLinks = links(map, "/com/hdmapreforged/route/links.tsv");
        List<int[]> gameLinks = gameLinkStarts();
        for (Transition t : map.transitions())
        {
            Edge.Kind kind = Tiles.z(t.to) != t.plane && Math.abs(Tiles.y(t.to) - t.y) < 1000 ? Edge.Kind.STAIRS
                : Edge.Kind.ENTRANCE;
            String name = t.action + (t.name.isEmpty() ? "" : " " + t.name);
            Fees.Fee fee = Fees.at(t.x, t.y, t.plane);
            String detail = fee != null ? "Needs: " + fee.text
                : t.through ? UNKNOWN_NEEDS : null;
            int cost = STAIRS + (fee == null ? 0 : fee.cost) + (t.through ? THROUGH : 0);
            for (int origin : t.origins)
            {
                if (sameSpot(origin, t.to) || confirmed(mapLinks, origin, t.to) || confirmed(handLinks, origin, t.to)
                    || !linkedElsewhere(gameLinks, origin))
                {
                    edges.add(new Edge(origin, t.to, kind, name, detail, cost));
                }
            }
        }
        edges.addAll(mapLinks);
        edges.addAll(handLinks);
        edges.addAll(fromOpenGround(map, edges));
        OneWay oneWay = OneWay.load();
        edges.removeIf(e -> oneWay.against(e.from, e.to));
        // A passage a gates.tsv row covers (a quest-locked city gate the game's map links have as open) is never free:
        // RouteSource offers the row's own edge to players who meet its needs.
        List<int[]> gated = new ArrayList<>();
        for (String[] parts : rows("/com/hdmapreforged/route/gates.tsv"))
        {
            int a = parts.length < 3 ? -1 : Tiles.parse(parts[1]);
            int b = a < 0 ? -1 : Tiles.parse(parts[2]);
            if (b >= 0)
            {
                gated.add(new int[]{a, b});
                close(a, b);
            }
        }
        edges.removeIf(e -> gated.stream().anyMatch(g -> covers(g, e)));
        // Passages that are Agility shortcuts (shortcuts.tsv): added only to requests whose player meets the shortcut.
        List<ShortcutPassage> atShortcuts = new ArrayList<>();
        edges.removeIf(e -> {
            // Its needs are checked per request: no toll for needs the map would not know.
            ShortcutPassage at = ShortcutPassage.of(UNKNOWN_NEEDS.equals(e.detail)
                ? new Edge(e.from, e.to, e.kind, e.name, null, e.cost - THROUGH) : e);
            return at != null && atShortcuts.add(at);
        });
        shortcutPassages = Collections.unmodifiableList(atShortcuts);
        passages = Collections.unmodifiableList(edges);
        passagesByOrigin = EdgeIndex.of(edges, 0);
        heuristicPassages = edges.stream().filter(e -> shortCut(e, RUN)).collect(Collectors.toUnmodifiableList());
        walkingPassages = edges.stream().filter(e -> shortCut(e, WALK)).collect(Collectors.toUnmodifiableList());
    }

    public List<ShortcutPassage> shortcutPassages()
    {
        return shortcutPassages;
    }

    /** Within a gate row's reach: the same floor, two tiles. */
    private static boolean near(int a, int b)
    {
        return Tiles.z(a) == Tiles.z(b) && Tiles.distance(a, b) <= 2;
    }

    /**
     * Whether a gates.tsv row (from, to) covers a passage: one going its own way, from near its start to near its end. Not
     * the way back: where a gate's two ends lie within 2 tiles of each other (Lletya's trees, Villa Lucens' entryway), the
     * passage out is near both ends too, and was taken for the gate's own (a row stands for one direction, as its closed
     * step does).
     */
    static boolean covers(int[] gate, Edge e)
    {
        return near(e.from, gate[0]) && near(e.to, gate[1])
            && Tiles.distance(e.from, gate[0]) <= Tiles.distance(e.from, gate[1])
            && Tiles.distance(e.to, gate[1]) <= Tiles.distance(e.to, gate[0]);
    }

    /** A gates.tsv row between neighbouring tiles: that step is not walked, only taken by the row's edge. */
    private void close(int from, int to)
    {
        if (Tiles.z(from) != Tiles.z(to) || Tiles.distance(from, to) != 1)
        {
            return;
        }
        for (int d = 0; d < DX.length; d++)
        {
            if (Tiles.x(to) - Tiles.x(from) == DX[d] && Tiles.y(to) - Tiles.y(from) == DY[d])
            {
                int known = closedSteps.get(from);
                closedSteps.put(from, (known == IntMap.MISSING ? 0 : known) | 1 << d);
            }
        }
    }

    static boolean sameSpot(int from, int to)
    {
        return Tiles.distance(from, to) <= SAME_SPOT;
    }

    /** A cache passage this near its start is the other side of an obstacle or the same spot on another floor. */
    static final int SAME_SPOT = 10;

    /** Where the game's own map links start (route/map_link_starts.tsv): x, y, plane. */
    private static List<int[]> gameLinkStarts()
    {
        List<int[]> starts = new ArrayList<>();
        for (String[] parts : rows("/com/hdmapreforged/route/map_link_starts.tsv"))
        {
            int start = Tiles.parse(parts[0]);
            if (start >= 0)
            {
                starts.add(new int[]{Tiles.x(start), Tiles.y(start), Tiles.z(start)});
            }
        }
        return starts;
    }

    /** A game map link starts within 4 tiles: where it leads is the game's word, not the cache's pairing. */
    private static boolean linkedElsewhere(List<int[]> starts, int origin)
    {
        return starts.stream().anyMatch(s -> s[2] == Tiles.z(origin) && Math.abs(s[0] - Tiles.x(origin)) <= 4
            && Math.abs(s[1] - Tiles.y(origin)) <= 4);
    }

    /**
     * A passage whose start is a tile shut in by walls (the snapped tile behind a cave mouth) is also taken from the
     * open tiles right beside it, where one stands to use it in the game.
     */
    private static List<Edge> fromOpenGround(CollisionMap map, List<Edge> edges)
    {
        Map<Integer, Boolean> open = new HashMap<>();
        List<Edge> extra = new ArrayList<>();
        for (Edge e : edges)
        {
            if (e.from < 0 || Tiles.isSea(e.from) || open.computeIfAbsent(e.from, n -> reaches(map, n, at -> false)))
            {
                continue;
            }
            for (int d = 0; d < 8; d++)
            {
                int x = Tiles.x(e.from) + DX[d];
                int y = Tiles.y(e.from) + DY[d];
                int next = Tiles.pack(x, y, Tiles.z(e.from));
                if (map.walkable(x, y, Tiles.z(e.from)) && open.computeIfAbsent(next, n -> reaches(map, n, at -> false)))
                {
                    extra.add(new Edge(next, e.to, e.kind, e.name, e.detail, e.cost));
                }
            }
        }
        return extra;
    }

    /** Tiles a walk reaches out in the open, not shut in by walls. */
    private static final int OPEN = 120;

    /** Whether walking from a tile reaches {@link #OPEN} tiles, or one that {@code way} leads out of. */
    private static boolean reaches(CollisionMap map, int node, IntPredicate way)
    {
        // Run for every passage at start: no boxing.
        int[] queue = new int[OPEN];
        IntMap seen = new IntMap(2 * OPEN);
        queue[0] = node;
        seen.put(node, 0);
        for (int head = 0, tail = 1; head < tail; head++)
        {
            int at = queue[head];
            if (way.test(at))
            {
                return true;
            }
            for (int d = 0; d < 4; d++)
            {
                int next = Tiles.pack(Tiles.x(at) + DX[d], Tiles.y(at) + DY[d], Tiles.z(at));
                if (map.canStep(Tiles.x(at), Tiles.y(at), Tiles.z(at), DX[d], DY[d]) && seen.get(next) == IntMap.MISSING)
                {
                    seen.put(next, 0);
                    if (++tail >= OPEN)
                    {
                        return true;
                    }
                    queue[tail - 1] = next;
                }
            }
        }
        return false;
    }

    private static boolean confirmed(List<Edge> passages, int from, int to)
    {
        return passages.stream().anyMatch(e -> Tiles.z(e.from) == Tiles.z(from) && Tiles.distance(e.from, from) <= 4
            && Tiles.z(e.to) == Tiles.z(to) && Tiles.distance(e.to, to) <= 16);
    }

    private static List<Edge> links(CollisionMap map, String resource)
    {
        List<Edge> edges = new ArrayList<>();
        for (String[] parts : rows(resource))
        {
            int a = parts.length < 3 ? -1 : Tiles.parse(parts[0]);
            int b = a < 0 ? -1 : Tiles.parse(parts[1]);
            int from = b < 0 ? -1 : map.nearestWalkable(Tiles.x(a), Tiles.y(a), Tiles.z(a), 2);
            int to = from < 0 ? -1 : map.nearestWalkable(Tiles.x(b), Tiles.y(b), Tiles.z(b), 2);
            if (to >= 0)
            {
                // An optional fourth column: its cost in half ticks, where it takes longer than stairs.
                int cost = parts.length > 3 && parts[3].trim().matches("\\d{1,4}") ? Integer.parseInt(parts[3].trim())
                    : STAIRS;
                edges.add(new Edge(from, to, Edge.Kind.ENTRANCE, parts[2].trim(), null, cost));
            }
        }
        return edges;
    }

    /** A bundled table's lines split at tabs, without {@code #} comments; what could be read. */
    public static List<String[]> rows(String resource)
    {
        List<String[]> rows = new ArrayList<>();
        InputStream in = RouteFiles.open(resource);
        if (in != null)
        {
            try
            {
                rows(in, 0, rows::add);
            }
            catch (IOException e)
            {
                // Those read so far.
            }
        }
        return rows;
    }

    /** Each line but {@code #} comments, split as {@code line.split("\t", limit)} does; closes {@code in}. */
    static void rows(InputStream in, int limit, Consumer<String[]> each) throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            for (String line = reader.readLine(); line != null; line = reader.readLine())
            {
                if (!line.startsWith("#"))
                {
                    each.accept(line.split("\t", limit));
                }
            }
        }
    }

    /** A cell's space-separated integers ("2920 4700" {@literal ->} {2920, 4700}). */
    public static int[] ints(String cell)
    {
        return Arrays.stream(cell.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
    }

    /** Folded y: most dungeons lie 6400 tiles north of what is above them. */
    private static int fy(int node)
    {
        int y = Tiles.y(node);
        return y >= 6400 ? y - 6400 : y;
    }

    /** Round the fold: one step from y 6399 to 6400 goes from the top of the folded range to its bottom. */
    static int folded(int a, int b)
    {
        int dy = Math.abs(fy(a) - fy(b));
        return Math.max(Math.abs(Tiles.x(a) - Tiles.x(b)), Math.min(dy, 6400 - dy));
    }

    /** Jumps the heuristic must know (a tile costing {@code step}), else it would overestimate and miss the best way. */
    private static boolean shortCut(Edge e, int step)
    {
        if (e.from == Edge.ANYWHERE)
        {
            return false;
        }
        int length = folded(e.from, e.to);
        return length > LONG_JUMP || e.cost < length * step;
    }

    public int resolveGoal(int target)
    {
        if (Tiles.isSea(target))
        {
            return target;
        }
        int x = Tiles.x(target);
        int y = Tiles.y(target);
        int z = Tiles.z(target);
        if (map.walkable(x, y, z))
        {
            return target;
        }
        // No ground on this floor around the spot, but the very spot is ground on another floor (a dungeon's raised part
        // the map shows as floor 1, ours as floor 2: the Brimhaven Dungeon's at 2633 9491): that floor's spot.
        if (map.nearestWalkable(x, y, z, 2) < 0)
        {
            for (int dz = 1; dz < 4; dz++)
            {
                for (int other : new int[]{z - dz, z + dz})
                {
                    if (other >= 0 && other <= 3 && map.walkable(x, y, other) && !pocket(Tiles.pack(x, y, other)))
                    {
                        return Tiles.pack(x, y, other);
                    }
                }
            }
        }
        int near = nearestOpen(x, y, z, SNAP_RADIUS);
        if (near >= 0 && !pocket(near))
        {
            return near;
        }
        if (z == 0 && sea != null && sea.sailable(x / Tiles.CELL, y / Tiles.CELL, 99))
        {
            // Open water with only an islet or a ledge near (a sea creature): sailed to, not walked to the islet.
            return Tiles.seaAt(x, y);
        }
        // Only a pocket here: the spot may be given on another floor than the ground it marks.
        int there = otherFloor(x, y, z, 3, true);
        if (there >= 0 || near >= 0)
        {
            return there >= 0 ? there : near;
        }
        // A spot given on the wrong floor: the same spot on another floor first.
        near = otherFloor(x, y, z, 1, false);
        if (near < 0)
        {
            near = otherFloor(x, y, z, SNAP_RADIUS, false);
        }
        return near;
    }

    /** {@code open}: the nearest open non-pocket tile, else any walkable one. */
    private int otherFloor(int x, int y, int z, int radius, boolean open)
    {
        for (int dz = 1; dz < 4; dz++)
        {
            for (int other : new int[]{z - dz, z + dz})
            {
                int near = other < 0 || other > 3 ? -1
                    : open ? nearestOpen(x, y, other, radius) : map.nearestWalkable(x, y, other, radius);
                if (near >= 0 && !(open && pocket(near)))
                {
                    return near;
                }
            }
        }
        return -1;
    }

    /** A start the data calls unwalkable (a boat's deck, a stepping stone) begins from the nearest open tile. */
    public int startNode(int start)
    {
        if (start < 0 || Tiles.isSea(start) || map.walkable(Tiles.x(start), Tiles.y(start), Tiles.z(start)))
        {
            return start;
        }
        int near = nearestOpen(Tiles.x(start), Tiles.y(start), Tiles.z(start), 5);
        return near >= 0 ? near : start;
    }

    /** Nearest walkable non-pocket tile, else nearest walkable, else -1. */
    private int nearestOpen(int x, int y, int z, int radius)
    {
        int fallback = -1;
        for (int r = 1; r <= radius; r++)
        {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !map.walkable(x + dx, y + dy, z))
                    {
                        continue;
                    }
                    int node = Tiles.pack(x + dx, y + dy, z);
                    int d = dx * dx + dy * dy;
                    if (fallback < 0)
                    {
                        fallback = node;
                    }
                    if (d < bestDistance && !pocket(node))
                    {
                        best = node;
                        bestDistance = d;
                    }
                }
            }
            if (best >= 0)
            {
                return best;
            }
        }
        return fallback;
    }

    /** A small shut-in area (behind bank booths, between stepping stones) with no passages. */
    public boolean pocket(int node)
    {
        return !reaches(map, node, at -> passagesByOrigin.get(at) != null);
    }

    public Route find(RouteRequest request, BooleanSupplier cancelled)
    {
        Route route = walkOrSail(request, cancelled);
        // Kept out of the Wilderness, yet no way around it gets there (the Mage Arena's bank, Entrana's dungeon without a
        // boat): through it after all, and the route says so.
        if (avoids(request) && route.keptOut && (route.outcome == Route.Outcome.NEAREST
            || route.outcome == Route.Outcome.NONE) && !cancelled.getAsBoolean())
        {
            Route through = walkOrSail(request.throughWilderness(), cancelled);
            if (through.outcome == Route.Outcome.FOUND)
            {
                return through.throughWilderness();
            }
            // Out of reach only if the search through it tried everything too.
            return through.outcome == Route.Outcome.CANCELLED || through.exhausted ? route : route.unproved();
        }
        return route;
    }

    private Route walkOrSail(RouteRequest request, BooleanSupplier cancelled)
    {
        Route route = search(request, cancelled);
        // Left without their boat (a place on land): sail only when walking does not get there (an island).
        if (request.withBoat != null && (route.outcome == Route.Outcome.NEAREST || route.outcome == Route.Outcome.NONE)
            && !cancelled.getAsBoolean())
        {
            Route sailed = search(request.withBoat, cancelled);
            return sailed.outcome == Route.Outcome.FOUND ? sailed : sailed.keptOut && !route.keptOut ? route.keptOut()
                : route;
        }
        return route;
    }

    /** Whether a search for this request stays out of the Wilderness: asked to, and it neither starts nor ends there. */
    private boolean avoids(RouteRequest request)
    {
        int start = startNode(request.start);
        return request.avoidWilderness && !Tiles.wilderness(request.target) && !(start >= 0 && Tiles.wilderness(start));
    }

    private Route search(RouteRequest request, BooleanSupplier cancelled)
    {
        int target = request.target;
        int goal = resolveGoal(target);
        List<Edge> all = new ArrayList<>(passages.size() + request.edges.size() + request.startEdges.size());
        all.addAll(passages);
        int requestOffset = all.size();
        all.addAll(request.edges);
        int startOffset = all.size();
        all.addAll(request.startEdges);
        EdgeIndex requestByOrigin = EdgeIndex.of(request.edges, requestOffset);
        int towards = goal >= 0 ? goal : target;
        int step = request.running ? RUN : WALK;
        // Walking, each tile costs a whole tick; at sea a half one (a block of 4 tiles costs 2 ticks).
        int scale = request.sailingLevel < 0 ? step : RUN;
        List<Edge> jumps = new ArrayList<>(scale == RUN ? heuristicPassages : walkingPassages);
        List<Integer> key = new ArrayList<>(List.of(towards, scale));
        for (Edge e : request.edges)
        {
            if (shortCut(e, scale))
            {
                jumps.add(e);
                key.addAll(List.of(e.from, e.to, e.cost));
            }
        }
        Heuristic heuristic = new Heuristic(bounds.computeIfAbsent(key, k -> Heuristic.bounds(jumps, towards, scale)),
            towards, scale);

        NodeTable nodes = new NodeTable();
        LongHeap open = new LongHeap();
        int start = startNode(request.start);
        // Out of the Wilderness, unless the route starts or ends there (from a house, start is -1: not in it).
        boolean avoid = avoids(request);
        if (start >= 0)
        {
            relax(nodes, open, heuristic, start, 0, NodeTable.START);
        }
        // Whether a step or jump into the Wilderness was left out (else going through it gets nowhere further).
        boolean keptOut = jumps(IntStream.range(startOffset, all.size()).toArray(), all, nodes, open, heuristic, 0, avoid);
        int best = -1;
        long bestScore = Long.MAX_VALUE;
        int popped = 0;
        boolean limited = false;
        int found = -1;
        while (!open.isEmpty())
        {
            long item = open.pop();
            int node = (int) item;
            int g = nodes.cost(node);
            if ((int) (item >>> 32) != g + heuristic.h(node))
            {
                continue;
            }
            if ((++popped & 4095) == 0 && cancelled.getAsBoolean())
            {
                return new Route(Route.Outcome.CANCELLED, Collections.emptyList(), 0, target, -1, false, false, popped);
            }
            if (node == goal)
            {
                found = node;
                break;
            }
            if (!Tiles.isSea(node))
            {
                long score = (long) (Math.max(Math.abs(Tiles.x(node) - Tiles.x(target)), Math.abs(Tiles.y(node) - Tiles.y(target)))
                    + (Tiles.z(node) != Tiles.z(target) ? 32 : 0)) << 32 | g;
                if (score < bestScore)
                {
                    bestScore = score;
                    best = node;
                }
            }
            if (nodes.size() > request.nodeLimit)
            {
                limited = true;
                break;
            }
            if (Tiles.isSea(node))
            {
                int cx = Tiles.cellX(node);
                int cy = Tiles.cellY(node);
                for (int d = 0; d < 8; d++)
                {
                    int nx = cx + DX[d];
                    int ny = cy + DY[d];
                    if (sea.sailable(nx, ny, request.sailingLevel))
                    {
                        relax(nodes, open, heuristic, Tiles.sea(nx, ny), g + SAIL, -1 - d);
                    }
                }
            }
            else
            {
                int x = Tiles.x(node);
                int y = Tiles.y(node);
                int z = Tiles.z(node);
                int closed = closedSteps.get(node);
                for (int d = 0; d < 8; d++)
                {
                    if (closed != IntMap.MISSING && (closed & 1 << d) != 0)
                    {
                        continue;
                    }
                    if (map.canStep(x, y, z, DX[d], DY[d]))
                    {
                        int next = Tiles.pack(x + DX[d], y + DY[d], z);
                        if (avoid && Tiles.wilderness(next))
                        {
                            keptOut = true;
                            continue;
                        }
                        int cost = step + (d < 4 && map.door(x, y, z, DX[d], DY[d]) ? DOOR : 0)
                            + (map.isObstacle(x + DX[d], y + DY[d], next) ? OBSTACLE : 0);
                        relax(nodes, open, heuristic, next, g + cost, -1 - d);
                    }
                }
                keptOut |= jumps(passagesByOrigin.get(node), all, nodes, open, heuristic, g, avoid);
            }
            keptOut |= jumps(requestByOrigin.get(node), all, nodes, open, heuristic, g, avoid);
        }
        boolean exhausted = found < 0 && !limited;
        int end = found >= 0 ? found : best;
        // Tried everything: what it reached tells where else the target is out of reach.
        Reached reached = exhausted ? nodes.reached() : null;
        Route route;
        if (end < 0)
        {
            route = new Route(Route.Outcome.NONE, Collections.emptyList(), 0, target, -1, exhausted, limited, popped,
                reached);
        }
        else
        {
            route = new Route(found >= 0 ? Route.Outcome.FOUND : Route.Outcome.NEAREST, steps(nodes, all, startOffset, end),
                nodes.cost(end), target, end, exhausted, limited, popped, reached);
        }
        return keptOut ? route.keptOut() : route;
    }

    /** Relaxes the jumps; whether one into the Wilderness was left out. */
    private static boolean jumps(int[] edges, List<Edge> all, NodeTable nodes, LongHeap open, Heuristic heuristic, int g,
        boolean avoidWilderness)
    {
        if (edges == null)
        {
            return false;
        }
        boolean keptOut = false;
        for (int i : edges)
        {
            Edge e = all.get(i);
            if (!(avoidWilderness && Tiles.wilderness(e.to)))
            {
                relax(nodes, open, heuristic, e.to, g + e.cost, i);
            }
            else
            {
                keptOut = true;
            }
        }
        return keptOut;
    }

    private static void relax(NodeTable nodes, LongHeap open, Heuristic heuristic, int to, int cost, int via)
    {
        if (cost < nodes.cost(to))
        {
            nodes.put(to, cost, via);
            open.push(key(cost + heuristic.h(to), to));
        }
    }

    private static long key(int f, int node)
    {
        return (long) f << 32 | (node & 0xffffffffL);
    }

    /** The node before on the way found: where its jump or step began; -1 at the start (or a jump from anywhere). */
    private static int parent(NodeTable nodes, List<Edge> all, int startOffset, int node)
    {
        int via = nodes.via(node);
        int d = -1 - via;
        return via >= startOffset || via == NodeTable.START ? -1 : via >= 0 ? all.get(via).from : Tiles.isSea(node)
            ? Tiles.sea(Tiles.cellX(node) - DX[d], Tiles.cellY(node) - DY[d])
            : Tiles.pack(Tiles.x(node) - DX[d], Tiles.y(node) - DY[d], Tiles.z(node));
    }

    private List<Route.Step> steps(NodeTable nodes, List<Edge> all, int startOffset, int end)
    {
        List<Route.Step> reversed = new ArrayList<>();
        List<Integer> run = new ArrayList<>();
        int node = end;
        run.add(node);
        while (true)
        {
            int via = nodes.via(node);
            int parent = parent(nodes, all, startOffset, node);
            if (via >= 0)
            {
                flush(reversed, run, nodes);
                Edge e = all.get(via);
                int from = parent >= 0 ? parent : node;
                reversed.add(new Route.Step(Route.Step.Kind.valueOf(e.kind.name()), new int[]{from, node}, e.name,
                    e.detail, e.time, 0, Collections.emptyList(), e.category));
                run.clear();
            }
            if (parent < 0)
            {
                break;
            }
            run.add(parent);
            node = parent;
        }
        flush(reversed, run, nodes);
        Collections.reverse(reversed);
        return reversed;
    }

    /** {@code run} is collected backwards. */
    private void flush(List<Route.Step> reversed, List<Integer> run, NodeTable nodes)
    {
        if (run.size() >= 2)
        {
            int[] points = new int[run.size()];
            int doors = 0;
            for (int i = 0; i < points.length; i++)
            {
                points[i] = run.get(points.length - 1 - i);
            }
            for (int i = 1; i < points.length; i++)
            {
                int a = points[i - 1];
                int b = points[i];
                int dx = Tiles.x(b) - Tiles.x(a);
                int dy = Tiles.y(b) - Tiles.y(a);
                if (!Tiles.isSea(a) && (dx == 0 || dy == 0) && map.door(Tiles.x(a), Tiles.y(a), Tiles.z(a), dx, dy))
                {
                    doors++;
                }
            }
            int cost = nodes.cost(points[points.length - 1]) - nodes.cost(points[0]);
            boolean sailing = Tiles.isSea(points[0]);
            List<Route.Obstacle> obstacles = new ArrayList<>();
            StringBuilder detail = new StringBuilder();
            if (!sailing)
            {
                points = GameWalk.straighten(map, GameWalk.follow(map, points));
                for (int point : points)
                {
                    String obstacle = map.obstacle(point);
                    if (obstacle != null)
                    {
                        obstacles.add(new Route.Obstacle(point, obstacle));
                        if (detail.indexOf(obstacle) < 0)
                        {
                            detail.append(detail.length() > 0 ? "; " : "").append(obstacle);
                        }
                    }
                }
            }
            reversed.add(new Route.Step(sailing ? Route.Step.Kind.SAIL : Route.Step.Kind.WALK, points, null,
                sailing ? hazards(points) : detail.length() > 0 ? detail.toString() : null, cost, doors, obstacles, null));
        }
        // Only the last point stays.
        run.subList(0, Math.max(0, run.size() - 1)).clear();
    }

    private String hazards(int[] points)
    {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < points.length; i += 3)
        {
            SeaMap.Area area = sea.nearest(Tiles.x(points[i]), Tiles.y(points[i]));
            if (area != null && area.level > 0 && !area.hazard.isEmpty())
            {
                names.add(area.hazard + " (" + area.name + ", " + area.level + " Sailing)");
            }
        }
        return names.isEmpty() ? null : "Crosses " + String.join(", ", names);
    }

    /** Folded distance, or via a jump's backward-searched bound; jump bounds cached per 8×8 block. */
    private static final class Heuristic
    {
        private final int goal;
        /** What a tile walked costs at least. */
        private final int scale;
        private final int[] origins;
        private final int[] bounds;
        /** Per 128 × 128 folded tiles, its 8 × 8 blocks' bounds once worked out (-1 not yet): a flood needs 30,000. */
        private final int[][] blocks = new int[1 << 14][];

        Heuristic(int[][] made, int goal, int scale)
        {
            this.goal = goal;
            this.scale = scale;
            origins = made[0];
            bounds = made[1];
        }

        /** Per jump origin, the least cost from there to the goal by jumps, walking between them as the crow flies. */
        static int[][] bounds(List<Edge> jumps, int goal, int scale)
        {
            int n = jumps.size();
            long[] bound = new long[n];
            boolean[] done = new boolean[n];
            int[] from = new int[n];
            int[] to = new int[n];
            int[] cost = new int[n];
            for (int i = 0; i < n; i++)
            {
                Edge e = jumps.get(i);
                from[i] = e.from;
                to[i] = e.to;
                cost[i] = e.cost;
                bound[i] = (long) e.cost + scale * folded(e.to, goal);
            }
            // Backward Dijkstra over the jumps.
            for (int round = 0; round < n; round++)
            {
                int pick = -1;
                for (int i = 0; i < n; i++)
                {
                    if (!done[i] && (pick < 0 || bound[i] < bound[pick]))
                    {
                        pick = i;
                    }
                }
                done[pick] = true;
                for (int i = 0; i < n; i++)
                {
                    bound[i] = done[i] ? bound[i]
                        : Math.min(bound[i], cost[i] + scale * folded(to[i], from[pick]) + bound[pick]);
                }
            }
            IntMap byOrigin = new IntMap(n);
            int count = 0;
            int[] keys = new int[n];
            for (int i = 0; i < n; i++)
            {
                int b = (int) Math.min(Integer.MAX_VALUE, bound[i]);
                int known = byOrigin.get(from[i]);
                if (known == IntMap.MISSING)
                {
                    keys[count++] = from[i];
                }
                if (known == IntMap.MISSING || b < known)
                {
                    byOrigin.put(from[i], b);
                }
            }
            int[][] made = {Arrays.copyOf(keys, count), new int[count]};
            for (int i = 0; i < count; i++)
            {
                made[1][i] = byOrigin.get(keys[i]);
            }
            return made;
        }

        int h(int node)
        {
            int direct = scale * folded(node, goal);
            if (origins.length == 0)
            {
                return direct;
            }
            int bx = Tiles.x(node) / BLOCK;
            int by = fy(node) / BLOCK;
            int[] area = blocks[(bx >> 4) << 7 | by >> 4];
            if (area == null)
            {
                area = new int[256];
                Arrays.fill(area, -1);
                blocks[(bx >> 4) << 7 | by >> 4] = area;
            }
            int cached = area[(bx & 15) << 4 | by & 15];
            if (cached < 0)
            {
                int cx = bx * BLOCK + BLOCK / 2;
                int cy = by * BLOCK + BLOCK / 2;
                int min = Integer.MAX_VALUE;
                for (int i = 0; i < origins.length; i++)
                {
                    int d = Math.max(Math.abs(Tiles.x(origins[i]) - cx), Math.abs(fy(origins[i]) - cy));
                    min = Math.min(min, scale * Math.max(0, d - BLOCK / 2) + bounds[i]);
                }
                cached = min;
                area[(bx & 15) << 4 | by & 15] = cached;
            }
            return Math.min(direct, cached);
        }
    }
}
