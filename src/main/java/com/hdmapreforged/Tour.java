package com.hdmapreforged;

import com.hdmapreforged.route.*;
import java.util.*;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * A custom route: stops (a place, or a kind of place resolved to the nearest one) and the ways it leaves out, kept as
 * text in settings; the same text, with {@code |} between the cells, is what Export copies and Import reads.
 */
final class Tour
{
    @RequiredArgsConstructor
    static final class Stop
    {
        final String name;
        final WorldPoint point;
        /** Null for a fixed place. */
        final String kind;

        static Stop place(String name, WorldPoint point)
        {
            return new Stop(name, point, null);
        }

        static Stop kind(String kind)
        {
            return new Stop("Nearest of: " + kind, null, kind);
        }

        boolean isKind()
        {
            return kind != null;
        }

        boolean same(Stop other)
        {
            return isKind() ? kind.equals(other.kind) : !other.isKind() && point.equals(other.point);
        }
    }

    /** The fastest order is solved exactly over all of them. */
    static final int MAX_STOPS = 12;
    static final int MAX_TOURS = 50;
    static final int MAX_AVOIDED = 30;
    static final String HEADER = "# route";
    /** Between the cells of a shared route's lines, where a tab would be lost in a chat. */
    static final String SHARED = " | ";

    final String name;
    final List<Stop> stops;
    /** After the last stop, the first again. */
    final boolean loop;
    /**
     * Ways this route leaves out, as a route's steps name them ("Varrock Teleport") or every one of a kind
     * ({@link RouteSource#TYPE} + "Teleports"), in the order they were added. Swing thread.
     */
    final Set<String> avoid;

    Tour(String name, List<Stop> stops)
    {
        this(name, stops, false);
    }

    Tour(String name, List<Stop> stops, boolean loop)
    {
        this(name, stops, loop, Collections.emptySet());
    }

    Tour(String name, List<Stop> stops, boolean loop, Set<String> avoid)
    {
        this.name = name;
        this.stops = new ArrayList<>(stops);
        this.loop = loop;
        this.avoid = new LinkedHashSet<>(avoid);
    }

    /** A left-out way as shown: "Every Teleports" for a kind, else its name. */
    static String describe(String avoided)
    {
        return avoided.regionMatches(true, 0, RouteSource.TYPE, 0, RouteSource.TYPE.length())
            ? "Every " + avoided.substring(RouteSource.TYPE.length()) : avoided;
    }

    /** The last of {@code tours} with this name, else a new empty one added to them. */
    static Tour named(List<Tour> tours, String name)
    {
        Tour found = null;
        for (Tour t : tours)
        {
            if (t.name.equals(name))
            {
                found = t;
            }
        }
        if (found == null)
        {
            found = new Tour(name, new ArrayList<>());
            tours.add(found);
        }
        return found;
    }

    static String unique(List<Tour> tours, String name)
    {
        String candidate = name;
        for (int n = 2; ; n++)
        {
            String c = candidate;
            if (tours.stream().noneMatch(t -> t.name.equalsIgnoreCase(c)))
            {
                return candidate;
            }
            candidate = name + " " + n;
        }
    }

    /** Same as the stop before: skipped when run, but kept so it can be moved (there and back). */
    boolean repeats(int i)
    {
        return i > 0 && i < stops.size() && stops.get(i).same(stops.get(i - 1));
    }

    Tour withoutRepeats()
    {
        return new Tour(name, IntStream.range(0, stops.size()).filter(i -> !repeats(i)).mapToObj(stops::get)
            .collect(Collectors.toList()), loop, avoid);
    }

    Tour renamed(String newName)
    {
        return new Tour(newName, stops, loop, avoid);
    }

    Tour looping(boolean again)
    {
        return new Tour(name, stops, again, avoid);
    }

    /** As the crow flies, same floor first; the first place when {@code near} is unknown. */
    static WorldPoint nearest(KindIndex.Kind kind, WorldPoint near)
    {
        if (kind == null || kind.entries.isEmpty())
        {
            return null;
        }
        var best = kind.entries.get(0).point;
        if (near == null)
        {
            return best;
        }
        long bestDistance = Long.MAX_VALUE;
        for (var entry : kind.entries)
        {
            long dx = entry.point.getX() - near.getX();
            long dy = entry.point.getY() - near.getY();
            long d = dx * dx + dy * dy + (entry.point.getPlane() == near.getPlane() ? 0 : 100_000_000L);
            if (d < bestDistance)
            {
                best = entry.point;
                bestDistance = d;
            }
        }
        return best;
    }

    /**
     * Per route "# route\tname" ("\tloop" when it loops), then per stop "x y plane\tname" or "-\tname\tkind", and per
     * way left out "!\tname".
     */
    static String encode(List<Tour> tours)
    {
        return encode(tours, "\t");
    }

    /** The same with {@link #SHARED} between the cells: for the clipboard. */
    static String share(List<Tour> tours)
    {
        return encode(tours, SHARED);
    }

    static String encode(List<Tour> tours, String cell)
    {
        StringBuilder text = new StringBuilder();
        for (Tour tour : tours)
        {
            text.append(HEADER).append(cell).append(clean(tour.name)).append(tour.loop ? cell + "loop\n" : "\n");
            for (Stop stop : tour.stops)
            {
                if (stop.isKind())
                {
                    text.append('-').append(cell).append(clean(stop.name)).append(cell).append(clean(stop.kind));
                }
                else
                {
                    var p = stop.point;
                    text.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getPlane()).append(cell)
                        .append(clean(stop.name));
                }
                text.append('\n');
            }
            for (String avoided : tour.avoid)
            {
                text.append('!').append(cell).append(clean(avoided)).append('\n');
            }
        }
        return text.toString();
    }

    /** Bad lines are skipped, never an error; a line's cells may be parted by tabs or by {@link #SHARED}. */
    static List<Tour> decode(String text)
    {
        var tours = new ArrayList<Tour>();
        if (text == null)
        {
            return tours;
        }
        String name = null;
        boolean loop = false;
        var stops = new ArrayList<Stop>();
        var avoid = new LinkedHashSet<String>();
        for (String line : text.split("\r?\n"))
        {
            var cells = cells(line);
            if (cells[0].equals(HEADER) && cells.length > 1)
            {
                if (name != null)
                {
                    tours.add(new Tour(name, stops, loop, avoid));
                }
                name = cells[1];
                loop = cells.length > 2 && cells[2].equals("loop");
                stops = new ArrayList<>();
                avoid = new LinkedHashSet<>();
                continue;
            }
            if (name == null || line.trim().isEmpty())
            {
                continue;
            }
            if (cells[0].equals("!"))
            {
                if (cells.length > 1 && !cells[1].isEmpty() && avoid.size() < MAX_AVOIDED)
                {
                    avoid.add(cells[1]);
                }
                continue;
            }
            if (stops.size() >= MAX_STOPS)
            {
                continue;
            }
            if (cells.length >= 3 && cells[0].equals("-") && !cells[2].isEmpty())
            {
                stops.add(new Stop(cells[1], null, cells[2]));
                continue;
            }
            int at = Tiles.parse(cells[0]);
            if (at >= 0)
            {
                int x = Tiles.x(at);
                int y = Tiles.y(at);
                stops.add(Stop.place(cells.length > 1 && !cells[1].isEmpty() ? cells[1] : x + ", " + y,
                    new WorldPoint(x, y, Tiles.z(at))));
            }
        }
        if (name != null)
        {
            tours.add(new Tour(name, stops, loop, avoid));
        }
        return tours.size() > MAX_TOURS ? new ArrayList<>(tours.subList(0, MAX_TOURS)) : tours;
    }

    /** A line's cells, trimmed: parted by tabs, or without any by {@link #SHARED} (a bare "|" counts too). */
    static String[] cells(String line)
    {
        var cells = line.indexOf('\t') >= 0 ? line.split("\t", -1) : line.split("\\s*\\|\\s*", -1);
        for (int i = 0; i < cells.length; i++)
        {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    /** Without what would part a line's cells or end it. */
    static String clean(String text)
    {
        return text.replaceAll("\\s*[\\t\\r\\n|]+\\s*", " ").trim();
    }

    /**
     * Exact open-path order from place 0 (the player); {@code cost[a][b]} negative means impossible. Returns places
     * 1.. in order, without the start.
     */
    static List<Integer> fastestOrder(long[][] cost)
    {
        return fastestOrder(cost, null);
    }

    /**
     * The same, where {@code once[a][b]} (when given) is the trip's cost with a teleport that can be cast once in the
     * route (a home or minigame teleport, with its cooldown): at most one trip takes it, the rest cost {@code cost}.
     */
    static List<Integer> fastestOrder(long[][] cost, long[][] once)
    {
        int n = cost.length - 1;
        if (n <= 0)
        {
            return new ArrayList<>();
        }
        if (n > MAX_STOPS)
        {
            throw new IllegalArgumentException("Too many stops: " + n);
        }
        long unreachable = Long.MAX_VALUE / 4;
        int full = 1 << n;
        // Per set of places visited, last place and whether the once-only teleport was taken (0 or 1).
        long[][][] best = new long[full][n][2];
        int[][][] from = new int[full][n][2];
        for (var row : best)
        {
            for (var used : row)
            {
                Arrays.fill(used, unreachable);
            }
        }
        for (int i = 0; i < n; i++)
        {
            best[1 << i][i][0] = weight(cost[0][i + 1], unreachable);
            from[1 << i][i][0] = -1;
            if (once != null)
            {
                best[1 << i][i][1] = weight(once[0][i + 1], unreachable);
                from[1 << i][i][1] = -1;
            }
        }
        for (int set = 1; set < full; set++)
        {
            for (int last = 0; last < n; last++)
            {
                for (int used = 0; used < 2; used++)
                {
                    long here = best[set][last][used];
                    if ((set & 1 << last) == 0 || here >= unreachable)
                    {
                        continue;
                    }
                    for (int next = 0; next < n; next++)
                    {
                        if ((set & 1 << next) != 0)
                        {
                            continue;
                        }
                        int with = set | 1 << next;
                        long via = here + weight(cost[last + 1][next + 1], unreachable);
                        if (via < best[with][next][used])
                        {
                            best[with][next][used] = via;
                            from[with][next][used] = last << 1 | used;
                        }
                        if (used == 0 && once != null)
                        {
                            long taking = here + weight(once[last + 1][next + 1], unreachable);
                            if (taking < best[with][next][1])
                            {
                                best[with][next][1] = taking;
                                from[with][next][1] = last << 1;
                            }
                        }
                    }
                }
            }
        }
        int end = 0;
        int endUsed = 0;
        for (int last = 0; last < n; last++)
        {
            for (int used = 0; used < 2; used++)
            {
                if (best[full - 1][last][used] < best[full - 1][end][endUsed])
                {
                    end = last;
                    endUsed = used;
                }
            }
        }
        var order = new ArrayList<Integer>();
        int set = full - 1;
        for (int at = end, used = endUsed; at >= 0; )
        {
            order.add(at + 1);
            int previous = from[set][at][used];
            set &= ~(1 << at);
            at = previous < 0 ? -1 : previous >> 1;
            used = previous < 0 ? 0 : previous & 1;
        }
        Collections.reverse(order);
        return order;
    }

    /** Impossible trips weigh a lot but the order still visits everything. */
    static long weight(long cost, long unreachable)
    {
        return cost < 0 ? unreachable / (MAX_STOPS + 2) : cost;
    }
}
