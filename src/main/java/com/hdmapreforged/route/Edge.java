package com.hdmapreforged.route;

import lombok.*;

/** A non-walking jump in the route graph; costs in half game ticks. */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class Edge
{
    public enum Kind
    {
        STAIRS,
        ENTRANCE,
        TELEPORT,
        TRANSPORT,
        SHIP,
        BOARD,
        DISEMBARK,
        HOUSE
    }

    public static final int ANYWHERE = -1;

    public final int from;
    public final int to;
    public final Kind kind;
    public final String name;
    public final String detail;
    /** As the search weighs it: {@link #time} plus any extra the player asked for. */
    public final int cost;
    public final int time;
    public final String category;

    public Edge(int from, int to, Kind kind, String name, String detail, int cost)
    {
        this(from, to, kind, name, detail, cost, null);
    }

    public Edge(int from, int to, Kind kind, String name, String detail, int cost, String category)
    {
        this(from, to, kind, name, detail, Math.max(1, cost), Math.max(1, cost), category);
    }

    /** Heavier in the search only ("only use a teleport when it saves at least 10 tiles"). */
    public Edge weighed(int extra)
    {
        return extra <= 0 ? this : new Edge(from, to, kind, name, detail, cost + extra, time, category);
    }

    @Override
    public String toString()
    {
        return kind + " " + name + " " + (from == ANYWHERE ? "anywhere" : Tiles.format(from)) + " -> " + Tiles.format(to);
    }
}
