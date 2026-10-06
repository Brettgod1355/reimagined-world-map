package com.hdmapreforged.route;

import java.util.*;
import java.util.stream.*;
import lombok.*;

/** The result of a search: the steps, and how the target was reached (or why not). */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class Route
{
    public enum Outcome
    {
        FOUND,
        NEAREST,
        NONE,
        CANCELLED
    }

    @AllArgsConstructor
    public static final class Obstacle
    {
        public final int at;
        public final String text;
    }

    @AllArgsConstructor
    public static final class Step
    {
        public enum Kind
        {
            WALK, SAIL, STAIRS, ENTRANCE, TELEPORT, TRANSPORT, SHIP, BOARD, DISEMBARK, HOUSE
        }

        public final Kind kind;
        /** Packed: all tiles walked or sea blocks sailed, or the two ends of a jump. */
        public final int[] points;
        public final String name;
        public final String detail;
        public final int cost;
        public final int doors;
        public final List<Obstacle> obstacles;
        /** Teleport or transport kind ("Hot air balloon"), to leave out all of that kind; or null. */
        public final String category;

        public int first()
        {
            return points[0];
        }

        public int last()
        {
            return points[points.length - 1];
        }

        public boolean isJump()
        {
            return kind != Kind.WALK && kind != Kind.SAIL;
        }
    }

    public final Outcome outcome;
    public final List<Step> steps;
    /** Half ticks. */
    public final int cost;
    public final int target;
    public final int end;
    public final boolean exhausted;
    public final boolean limited;
    public final int nodes;
    /** What a search that tried everything reached; else null. */
    private final Reached reached;
    /** Through the Wilderness though the player keeps out of it: no way around it gets there. */
    public final boolean throughWilderness;
    /** The search left out a step or jump into the Wilderness (kept out of it): through it, it may get further. */
    final boolean keptOut;

    /** Without the extra weight of teleports kept for longer trips. */
    public int time()
    {
        return steps.stream().mapToInt(step -> step.cost).sum();
    }

    public Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited,
        int nodes)
    {
        this(outcome, steps, cost, target, end, exhausted, limited, nodes, null);
    }

    Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited, int nodes,
        Reached reached)
    {
        this(outcome, Collections.unmodifiableList(steps), cost, target, end, exhausted, limited, nodes, reached, false,
            false);
    }

    /** This route, found through the Wilderness because no way around it gets there. */
    Route throughWilderness()
    {
        return new Route(outcome, steps, cost, target, end, exhausted, limited, nodes, reached, true, keptOut);
    }

    /** This route, of a search that left out ways into the Wilderness. */
    Route keptOut()
    {
        return new Route(outcome, steps, cost, target, end, exhausted, limited, nodes, reached, throughWilderness, true);
    }

    /**
     * This route, not proof that the target is out of reach: the search through the Wilderness after it stopped at the
     * node limit.
     */
    Route unproved()
    {
        return new Route(outcome, steps, cost, target, end, false, limited, nodes, null, throughWilderness, keptOut);
    }

    public static final int NEAR = 12;

    /** What is still ahead of the player, like a navigation app; the route itself when the player is not near it. */
    public Route ahead(int player)
    {
        if (player < 0 || steps.isEmpty())
        {
            return this;
        }
        int bestStep = -1;
        int bestIndex = 0;
        long bestDistance = (long) (NEAR + 1) * (NEAR + 1);
        for (int s = 0; s < steps.size(); s++)
        {
            Step step = steps.get(s);
            if (step.isJump())
            {
                int landing = step.last();
                int from = step.first();
                // Nearer where it starts (a gate, a door): not crossed yet.
                boolean before = from >= 0 && !Tiles.isSea(from) && sameLevel(player, from)
                    && Tiles.distance(player, from) < Tiles.distance(player, landing);
                // Into one's house (a teleport, its portal): anywhere in it, it was taken, wherever the game put one.
                boolean entered = from >= 0 && !inHouse(from) && inHouse(landing) && inHouse(player);
                if (sameLevel(player, landing) && (Tiles.distance(player, landing) <= 2 || entered) && !before)
                {
                    bestStep = s + 1;
                    bestIndex = 0;
                    bestDistance = 0;
                }
                continue;
            }
            for (int i = 0; i < step.points.length; i++)
            {
                int point = step.points[i];
                if (!sameLevel(player, point))
                {
                    continue;
                }
                long dx = Tiles.x(player) - Tiles.x(point);
                long dy = Tiles.y(player) - Tiles.y(point);
                long d = dx * dx + dy * dy;
                if (Tiles.isSea(point))
                {
                    d /= (long) Tiles.CELL * Tiles.CELL;
                }
                // Ties go to the later point, so a route passing a spot twice follows the player forward.
                if (d <= bestDistance)
                {
                    bestStep = s;
                    bestIndex = i;
                    bestDistance = d;
                }
            }
        }
        if (bestStep < 0 || bestStep == 0 && bestIndex == 0)
        {
            return this;
        }
        List<Step> left = new ArrayList<>();
        for (int s = bestStep; s < steps.size(); s++)
        {
            Step step = steps.get(s);
            if (s == bestStep && bestIndex > 0)
            {
                int[] rest = Arrays.copyOfRange(step.points, bestIndex, step.points.length);
                if (rest.length < 2)
                {
                    continue;
                }
                List<Obstacle> obstaclesLeft = step.obstacles.stream()
                    .filter(o -> Arrays.stream(rest).anyMatch(p -> p == o.at)).collect(Collectors.toList());
                step = new Step(step.kind, rest, step.name, step.detail, step.cost * rest.length / step.points.length,
                    step.doors, obstaclesLeft, step.category);
            }
            left.add(step);
        }
        return new Route(outcome, Collections.unmodifiableList(left), cost, target, end, exhausted, limited, nodes, null,
            throughWilderness, keptOut);
    }

    private static boolean inHouse(int node)
    {
        return HousePlan.contains(Tiles.x(node), Tiles.y(node));
    }

    private static boolean sameLevel(int a, int b)
    {
        return Tiles.isSea(a) || Tiles.isSea(b) || Tiles.z(a) == Tiles.z(b);
    }

    /**
     * Whether this search tried everything and reached the node: from there the target is out of reach too, as long as
     * the same ways may be used.
     */
    public boolean reached(int node)
    {
        return reached != null && reached.contains(node);
    }

    public boolean snapped()
    {
        return end >= 0 && end != target;
    }

    public boolean has(Step.Kind kind)
    {
        return first(kind) != null;
    }

    public Step first(Step.Kind kind)
    {
        return steps.stream().filter(step -> step.kind == kind).findFirst().orElse(null);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(outcome + " " + (cost + 1) / 2 + " ticks, " + nodes + " nodes");
        for (Step step : steps)
        {
            sb.append("\n  ").append(step.kind).append(' ').append(step.name == null ? "" : step.name).append(' ')
                .append(Tiles.format(step.first())).append(" -> ").append(Tiles.format(step.last()));
        }
        return sb.toString();
    }
}
