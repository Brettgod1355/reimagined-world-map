package com.hdmapreforged.route;

import java.io.*;
import java.util.*;

/** Route graph nodes as ints: a game tile, or with {@link #SEA} set a 4×4 block of sea tiles on the ground floor. */
public final class Tiles
{
    public static final int SEA = 1 << 30;
    public static final int CELL = 4;

    /** The Wilderness's safe zones and the ground south of its edge, read from wilderness.tsv when first asked. */
    static final class Zones
    {
        static final int[][] SAFE;
        static final int[][] OUTSIDE;

        static
        {
            var safe = new ArrayList<int[]>();
            var outside = new ArrayList<int[]>();
            try
            {
                Pathfinder.rows(Tiles.class.getResourceAsStream("wilderness.tsv"), 0,
                    c -> ("safe".equals(c[0]) ? safe : outside).add(Pathfinder.ints(c[1])));
            }
            catch (IOException | RuntimeException e)
            {
                // What was read.
            }
            SAFE = safe.toArray(new int[0][]);
            OUTSIDE = outside.toArray(new int[0][]);
        }
    }

    Tiles()
    {
    }

    public static int pack(int x, int y, int z)
    {
        return (z & 3) << 28 | (x & 0x3fff) << 14 | (y & 0x3fff);
    }

    public static int x(int node)
    {
        return isSea(node) ? cellX(node) * CELL + CELL / 2 : node >> 14 & 0x3fff;
    }

    public static int y(int node)
    {
        return isSea(node) ? cellY(node) * CELL + CELL / 2 : node & 0x3fff;
    }

    public static int z(int node)
    {
        return isSea(node) ? 0 : node >> 28 & 3;
    }

    public static boolean isSea(int node)
    {
        return (node & SEA) != 0;
    }

    public static int sea(int cellX, int cellY)
    {
        return SEA | (cellX & 0xfff) << 12 | (cellY & 0xfff);
    }

    public static int cellX(int node)
    {
        return node >> 12 & 0xfff;
    }

    public static int cellY(int node)
    {
        return node & 0xfff;
    }

    public static int seaAt(int x, int y)
    {
        return sea(x / CELL, y / CELL);
    }

    /** {@code "x y plane"} packed; -1 when it is not one. */
    public static int parse(String text)
    {
        var xyz = text.trim().split("\\s+");
        if (xyz.length != 3)
        {
            return -1;
        }
        for (String part : xyz)
        {
            // Too many digits: not a tile (and no overflow).

            if (part.isEmpty() || part.length() > 5 || !part.chars().allMatch(Character::isDigit))
            {
                return -1;
            }
        }
        int x = Integer.parseInt(xyz[0]);
        int y = Integer.parseInt(xyz[1]);
        int z = Integer.parseInt(xyz[2]);
        return x < 1 << 14 && y < 1 << 14 && z < 4 ? pack(x, y, z) : -1;
    }

    public static String format(int node)
    {
        return x(node) + ", " + y(node) + ", " + z(node) + (isSea(node) ? " (sea)" : "");
    }

    /**
     * In the Wilderness: above ground x 2944-3391, y 3520-3967, and its dungeons 6400 tiles north of that; not its safe
     * zones, nor the ground south of its edge ({@link Zones}).
     */
    public static boolean wilderness(int node)
    {
        if (isSea(node))
        {
            return false;
        }
        int x = x(node);
        int y = y(node);
        if (x < 2944 || x > 3391 || !(y >= 3520 && y <= 3967 || y >= 9920 && y <= 10367))
        {
            return false;
        }
        return !within(Zones.SAFE, x, y) && !within(Zones.OUTSIDE, x, y);
    }

    static boolean within(int[][] zones, int x, int y)
    {
        for (var zone : zones)
        {
            if (x >= zone[0] && x <= zone[1] && y >= zone[2] && y <= zone[3])
            {
                return true;
            }
        }
        return false;
    }

    /** Chebyshev distance in tiles, ignoring floors. */
    public static int distance(int a, int b)
    {
        return Math.max(Math.abs(x(a) - x(b)), Math.abs(y(a) - y(b)));
    }
}
