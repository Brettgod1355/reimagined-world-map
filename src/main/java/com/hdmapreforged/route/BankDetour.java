package com.hdmapreforged.route;

import static com.hdmapreforged.route.Route.Outcome.*;

import java.util.*;
import java.util.function.*;
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

    /** Added to the name of the teleport or transport that takes one to the bank. */
    public static final String TO_BANK = " (to bank)";

    /** A step's name without {@link #TO_BANK}: what it is left out of a route by. */
    public static String plain(String name)
    {
        return name != null && name.endsWith(TO_BANK) ? name.substring(0, name.length() - TO_BANK.length()) : name;
    }

    /** Half ticks to open the bank and take things out. */
    static final int WITHDRAW = 6;
    /** The detour must save at least this many half ticks (10 seconds). */
    static final int MIN_SAVED = 34;

    /**
     * Per bank, where to stand at it: beside each of its booths or chests, nearest its icon first (or the icon). The few
     * banks quickest to reach are tried, each at its first; the way in then goes to the booth nearest where it comes in.
     */
    final int[][] banks;
    /** The same search with the bank's items as well. */
    final RouteRequest withBank;

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
            || banked.cost + MIN_SAVED <= plain.cost);
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
        int nearer = nearest(stands, entry(toBank, stands[0]));
        if (nearer != stands[0])
        {
            // Another booth of the same bank, nearer where the way comes in: there if it is no slower.
            Route way = pathfinder.find(request.between(request.start, nearer), cancelled);
            toBank = way.outcome == FOUND && way.cost <= toBank.cost ? way : toBank;
        }
        int at = toBank.end >= 0 ? toBank.end : toBank.target;
        Route on = pathfinder.find(withBank.between(at, request.target), cancelled);
        if (on.outcome != FOUND)
        {
            return null;
        }
        var carried = new HashSet<String>();
        for (var ways : List.of(request.startEdges, request.edges))
        {
            ways.forEach(e -> carried.add(e.name));
        }
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
            else if (named != null || step.isJump() && step.name != null && !carried.contains(step.name))
            {
                take.add(taken(step));
            }
        }
        if (take.isEmpty())
        {
            return null;
        }
        var steps = new ArrayList<Route.Step>(toBank.steps);
        // The last teleport or transport on the way says it goes to the bank.
        for (int i = steps.size() - 1; i >= 0; i--)
        {
            var s = steps.get(i);
            if (s.isJump() && s.name != null)
            {
                steps.set(i, new Route.Step(s.kind, s.points, s.name + TO_BANK, s.detail, s.cost, s.doors, s.obstacles,
                    s.category));
                break;
            }
        }
        steps.add(new Route.Step(Route.Step.Kind.BANK, new int[]{at, at}, "Bank: get " + String.join(", ", take), null,
            WITHDRAW, 0, List.of(), null));
        steps.addAll(on.steps);
        return new Route(FOUND, steps, toBank.cost + WITHDRAW + on.cost, request.target, on.end, false,
            false, toBank.nodes + on.nodes);
    }

    /** Tiles from a booth within which a way counts as come into its bank. */
    static final int ENTRY = 8;

    /** Where {@code way} first comes within {@link #ENTRY} tiles of {@code stand} on its floor; else its end. */
    static int entry(Route way, int stand)
    {
        for (var step : way.steps)
        {
            for (int point : step.kind == Route.Step.Kind.WALK ? step.points : new int[0])
            {
                if (Tiles.z(point) == Tiles.z(stand) && Tiles.distance(point, stand) <= ENTRY)
                {
                    return point;
                }
            }
        }
        return way.end >= 0 ? way.end : stand;
    }

    /** Of {@code stands}, the one nearest {@code point} (the first of equals). */
    static int nearest(int[] stands, int point)
    {
        int best = stands[0];
        for (int stand : stands)
        {
            if (Tiles.z(stand) == Tiles.z(point) && Tiles.distance(stand, point) < Tiles.distance(best, point))
            {
                best = stand;
            }
        }
        return best;
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
        for (Edge e : request.startEdges)
        {
            from.add(new int[]{e.to, e.cost});
        }
        for (Edge e : request.edges)
        {
            // Out of one's house: its portals, the box, the glory and the like; a teleport cast once out of it (a
            // minigame teleport by the house portal).
            if (e.from >= 0 && (e.kind == Edge.Kind.TELEPORT || HousePlan.contains(e.from)
                && !HousePlan.contains(e.to)))
            {
                from.add(new int[]{e.to, e.cost + (e.kind == Edge.Kind.TELEPORT ? OUT_OF_HOUSE : 0)});
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
        if (step.category != null && step.category.endsWith("Spellbook"))
        {
            return "runes";
        }
        return step.kind == Route.Step.Kind.TELEPORT || step.kind == Route.Step.Kind.HOUSE ? what : "items for " + what;
    }
}
