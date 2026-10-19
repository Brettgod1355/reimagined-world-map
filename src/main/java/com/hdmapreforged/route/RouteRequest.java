package com.hdmapreforged.route;

import java.util.*;
import lombok.*;

/** Immutable. */
@AllArgsConstructor
public final class RouteRequest
{
    /**
     * Past it the search stops where it is, without a verdict. A flood of the world is about 1.7 million nodes (and
     * the search table about 100 MB), which only a far trip with no way there at all still takes: a target shut off
     * from the player is proved out of reach from its own side (Pathfinder's flood back from the goal).
     */
    public static final int DEFAULT_NODE_LIMIT = 3_000_000;

    /** -1 when the route can only start with {@link #startEdges} (in a house). */
    public final int start;
    public final int target;
    /** Teleports, and the ways out of a player-owned house. */
    public final List<Edge> startEdges;
    public final List<Edge> edges;
    /** -1 when they cannot sail their own boat. */
    public final int sailingLevel;
    public final boolean running;
    public final int nodeLimit;
    /**
     * Stay out of the Wilderness, unless the route starts or ends in it; when no way around it gets there, the route goes
     * through it ({@link #throughWilderness()}).
     */
    public final boolean avoidWilderness;
    /**
     * The same with their boat, searched when this one (left without it) does not arrive; else null. {@code withBoat}:
     * this one with it.
     */
    @With
    public final RouteRequest boat;
    /** A way past a bank first, for what is kept there (setting); else null. {@code withBank}: the same with one. */
    @With
    public final BankDetour bank;

    public RouteRequest(int start, int target, List<Edge> startEdges, List<Edge> edges, int sailingLevel, boolean running,
        int nodeLimit)
    {
        this(start, target, startEdges, edges, sailingLevel, running, nodeLimit, false);
    }

    public RouteRequest(int start, int target, List<Edge> startEdges, List<Edge> edges, int sailingLevel, boolean running,
        int nodeLimit, boolean avoidWilderness)
    {
        this(start, target, Collections.unmodifiableList(startEdges), Collections.unmodifiableList(edges), sailingLevel,
            running, nodeLimit, avoidWilderness, null, null);
    }

    /** The same ways between other ends. */
    public RouteRequest between(int start, int target)
    {
        return new RouteRequest(start, target, startEdges, edges, sailingLevel, running, nodeLimit, avoidWilderness,
            boat == null ? null : boat.between(start, target), null);
    }

    /** The same ways, through the Wilderness too: searched when staying out of it does not arrive. */
    public RouteRequest throughWilderness()
    {
        return new RouteRequest(start, target, startEdges, edges, sailingLevel, running, nodeLimit, false,
            boat == null ? null : boat.throughWilderness(), bank);
    }

    /** Whether a search may use the same ways as with {@code other} (jumps and sea): what it reaches differs by start only. */
    boolean sameWays(RouteRequest other)
    {
        return sailingLevel == other.sailingLevel && avoidWilderness == other.avoidWilderness
            && (boat == null) == (other.boat == null) && same(edges, other.edges)
            && same(startEdges, other.startEdges);
    }

    static boolean same(List<Edge> a, List<Edge> b)
    {
        for (int i = 0; i < a.size() && a.size() == b.size(); i++)
        {
            if (a.get(i).from != b.get(i).from || a.get(i).to != b.get(i).to)
            {
                return false;
            }
        }
        return a.size() == b.size();
    }
}
