package com.hdmapreforged.route;

import static com.hdmapreforged.route.Route.Outcome.*;

import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import lombok.*;

/**
 * A way past a bank first, for teleports and transport kept in the bank (setting, with the bank as last seen): to the
 * nearest of a few banks with what is carried, take out what the rest needs, and on from there with the bank's items
 * too. Taken instead of the way with what is carried only when it gets there notably sooner, or only it gets there.
 */
@RequiredArgsConstructor
public final class BankDetour
{
    /** How a step's detail starts when it needs something only the bank has (RouteSource names it). */
    public static final String FROM_BANK = "From the bank: ";

    /** Joins the name of the teleport or transport that takes one to the bank to what is taken there ("... Castle Wars → Bank: Dramen staff"). */
    public static final String TO_BANK = " → Bank: ";

    /** A step's name without {@link #TO_BANK} and what follows: what it is left out of a route by. */
    public static String plain(String name)
    {
        int at = name == null ? -1 : name.lastIndexOf(TO_BANK);
        return at < 0 ? name : name.substring(0, at);
    }

    /** Half ticks to open the bank and take things out. */
    static final int WITHDRAW = 6;

    /**
     * Per bank, where to stand at it: beside each of its booths or chests, nearest its icon first (or the icon). The few
     * banks quickest to reach are tried, each at its first; the way in then goes where the game walks from where it comes in.
     */
    final int[][] banks;
    /** The same search with the bank's items as well. */
    final RouteRequest withBank;
    /** Half ticks it must save: the setting's tiles, run. */
    final int saves;
    /** What the rune pouch in the bank holds, by name: taken as the pouch. */
    final Map<String, Long> pouch;

    /** The route for {@code request}: past a bank when its {@link RouteRequest#bank} makes it notably quicker. */
    public static Route find(Pathfinder pathfinder, RouteRequest request, BooleanSupplier cancelled)
    {
        Route plain = pathfinder.find(request, cancelled);
        var detour = request.bank;
        if (detour == null || plain.outcome == CANCELLED || cancelled.getAsBoolean())
        {
            return plain;
        }
        Route banked = detour.route(pathfinder, request, cancelled);
        boolean better = banked != null && (plain.outcome != FOUND
            || banked.cost + detour.saves <= plain.cost);
        return better && !cancelled.getAsBoolean() ? banked : plain;
    }

    /** To the nearest bank, then on with the bank's items; null when no bank is reached or nothing banked is used. */
    Route route(Pathfinder pathfinder, RouteRequest request, BooleanSupplier cancelled)
    {
        Route toBank = null;
        int[] stands = null;
        for (int bank : quickest(request))
        {
            Route way = pathfinder.find(request.between(request.start, banks[bank][0]), cancelled);
            if (way.outcome == FOUND && (toBank == null || way.cost < toBank.cost))
            {
                toBank = way;
                stands = banks[bank];
            }
        }
        if (toBank == null || cancelled.getAsBoolean())
        {
            return null;
        }
        int nearer = stand(pathfinder.map, stands, entry(toBank, stands[0]));
        if (nearer != stands[0])
        {
            // Another booth or side of the same bank, where the game walks from where the way comes in.
            Route way = pathfinder.find(request.between(request.start, nearer), cancelled);
            toBank = way.outcome == FOUND ? way : toBank;
        }
        int at = toBank.end >= 0 ? toBank.end : toBank.target;
        Route on = pathfinder.find(withBank.between(at, request.target), cancelled);
        if (on.outcome != FOUND)
        {
            return null;
        }
        // Only what the bank's items add (not a ladder or cave on the way, which no item opens).
        var fromBank = new HashSet<String>();
        // Not the ways back into one's house, which the plain search from inside it lacks: no item of the bank opens them.
        withBank.startEdges.stream().filter(e -> !HousePlan.contains(e.to)).forEach(e -> fromBank.add(e.name));
        withBank.edges.stream().filter(e -> !HousePlan.contains(e.to)).forEach(e -> fromBank.add(e.name));
        request.startEdges.forEach(e -> fromBank.remove(e.name));
        request.edges.forEach(e -> fromBank.remove(e.name));
        var take = new LinkedHashSet<String>();
        for (var step : on.steps)
        {
            String named = step.detail != null && step.detail.startsWith(FROM_BANK)
                ? step.detail.substring(FROM_BANK.length()) : null;
            // "items": the game had not named them yet.
            if (named != null && !named.equals("items"))
            {
                take.addAll(Arrays.asList(named.split(", ")));
            }
            else if (named != null || step.isJump() && fromBank.contains(step.name))
            {
                take.add(taken(step));
            }
        }
        if (take.isEmpty())
        {
            return null;
        }
        var taken = viaPouch(take);
        String items = String.join(", ", taken);
        var steps = new ArrayList<Route.Step>(toBank.steps);
        // The last teleport or transport on the way names what is taken at the bank.
        for (int i = steps.size() - 1; i >= 0; i--)
        {
            var s = steps.get(i);
            if (s.isJump() && s.name != null)
            {
                steps.set(i, s.withName(s.name + TO_BANK + items));
                break;
            }
        }
        steps.add(new Route.Step(Route.Step.Kind.BANK, new int[]{at, at}, "Bank: " + items, null,
            WITHDRAW, 0, List.of(), null));
        for (var s : on.steps)
        {
            // What the steps name from the bank: the pouch's runes as the pouch, as the bank step does.
            steps.add(s.detail != null && s.detail.startsWith(FROM_BANK)
                ? s.withDetail(FROM_BANK + String.join(", ", viaPouch(Arrays.asList(
                    s.detail.substring(FROM_BANK.length()).split(", "))))) : s);
        }
        return new Route(FOUND, steps, toBank.cost + WITHDRAW + on.cost, request.target, on.end, false,
            false, toBank.nodes + on.nodes);
    }

    /** What is taken: runes the pouch holds enough of (a combination rune in it counts as its two) as "Rune pouch". */
    Set<String> viaPouch(Collection<String> take)
    {
        var held = new HashMap<String, Long>();
        pouch.forEach((name, n) -> {
            if (name == null)
            {
                return;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            held.merge(lower, n, Long::sum);
            for (var c : COMBINED.get())
            {
                if (lower.equals(c[0] + " rune"))
                {
                    held.merge(c[1] + " rune", n, Long::sum);
                    held.merge(c[2] + " rune", n, Long::sum);
                }
            }
        });
        var taken = new LinkedHashSet<String>();
        for (String t : take)
        {
            var m = AMOUNT.matcher(t);
            taken.add(m.matches() && held.getOrDefault(m.group(2).toLowerCase(Locale.ROOT), 0L) >= (m.group(1) == null
                ? 1 : Long.parseLong(m.group(1))) ? "Rune pouch" : t);
        }
        return taken;
    }

    /** Combination runes and the two runes each holds (list "combination runes"). */
    static final RouteFiles.Table<String[][]> COMBINED = new RouteFiles.Table<>(() -> Pathfinder.list(
        "combination runes", r -> r.split(" ")).stream().filter(c -> c.length > 2).toArray(String[][]::new));

    static final Pattern AMOUNT = Pattern.compile("(?:(\\d+) × )?(.+)");

    /** Tiles from a booth within which a way counts as come into its bank. */
    static final int ENTRY = 8;

    /** Where {@code way} first comes within {@link #ENTRY} tiles of {@code stand} on its floor; else its end. */
    static int entry(Route way, int stand)
    {
        for (var step : way.steps)
        {
            for (int point : step.kind == Route.Step.Kind.WALK ? step.points : new int[0])
            {
                if (Tiles.near(point, stand, ENTRY))
                {
                    return point;
                }
            }
        }
        return way.end >= 0 ? way.end : stand;
    }

    /**
     * Of {@code stands}, where the game walks from {@code point} to use the bank: the first its search reaches (it tries
     * west, east, south, north, then the diagonals, so not always the nearest side), else the first.
     */
    static int stand(CollisionMap map, int[] stands, int point)
    {
        int[] walk = GameWalk.path(map, point, stands);
        return walk == null ? stands[0] : walk[walk.length - 1];
    }

    /** Half ticks a guess adds for leaving the house before a teleport cast outside it. */
    static final int OUT_OF_HOUSE = 12;

    /** Banks searched for the way to one: the quickest by {@link #guess}. */
    static final int TRIED = 3;

    /**
     * The {@link #TRIED} banks (indices) quickest to reach by a guess (no search): from where the player stands (not in a house,
     * whose plan lies far off the map) and from where each way out of it lands (a teleport, a house's portal, box or
     * glory), its time plus the run on in a straight line (a tile a half tick). Underground and above are apart, so a
     * bank 6400 tiles north is not counted as near.
     */
    int[] quickest(RouteRequest request)
    {
        var from = new ArrayList<int[]>();
        int start = request.start;
        if (start >= 0 && !HousePlan.contains(start))
        {
            from.add(new int[]{start, 0});
        }
        // Ways into the house (from outside it) come before any way out of it: its time, then the walk in the plan.
        var in = new ArrayList<int[]>();
        for (Edge e : request.startEdges)
        {
            from.add(new int[]{e.to, e.cost});
            if (start >= 0 && HousePlan.contains(e.to))
            {
                in.add(new int[]{e.to, e.cost});
            }
        }
        for (Edge e : request.edges)
        {
            // Out of one's house: its portals, the box, the glory and the like; a teleport cast once out of it (a
            // minigame teleport by the house portal).
            if (e.from >= 0 && (e.kind == Edge.Kind.TELEPORT || HousePlan.contains(e.from)
                && !HousePlan.contains(e.to)))
            {
                int enter = start >= 0 && !HousePlan.contains(start) ? Integer.MAX_VALUE / 4 : 0;
                for (int[] i : in)
                {
                    enter = Math.min(enter, i[1] + Tiles.distance(i[0], e.from) * Pathfinder.RUN);
                }
                from.add(new int[]{e.to, e.cost + (e.kind == Edge.Kind.TELEPORT ? OUT_OF_HOUSE : enter)});
            }
        }
        long[] guessed = new long[banks.length];
        for (int i = 0; i < banks.length; i++)
        {
            guessed[i] = (long) guess(from, banks[i][0]) << 32 | i;
        }
        Arrays.sort(guessed);
        return Arrays.stream(guessed, 0, Math.min(TRIED, banks.length)).mapToInt(g -> (int) g).toArray();
    }

    /** Half ticks from the nearest of {@code from} ({tile, half ticks to get there}) to the bank, as the crow flies. */
    static int guess(List<int[]> from, int bank)
    {
        int best = Integer.MAX_VALUE / 2;
        for (var f : from)
        {
            if (f[0] >= 0 && Tiles.y(f[0]) / 6400 == Tiles.y(bank) / 6400)
            {
                best = Math.min(best, f[1] + Tiles.distance(f[0], bank) * Pathfinder.RUN);
            }
        }
        return best;
    }

    /**
     * What a step takes from the bank when its items are not named (a house's fairy ring): "runes" for a spell, the item
     * of a teleport ("Ring of dueling"), else "items for" what it is ("items for Fairy ring").
     */
    public static String taken(Route.Step step)
    {
        String name = step.name;
        int arrow = name.indexOf(" → ");
        name = arrow > 0 ? name.substring(0, arrow) : name;
        int colon = name.indexOf(": ");
        String what = colon > 0 ? name.substring(0, colon) : name;
        return step.category != null && step.category.endsWith("Spellbook") ? "runes"
            : step.kind == Route.Step.Kind.TELEPORT || step.kind == Route.Step.Kind.HOUSE ? what : "items for " + what;
    }
}
