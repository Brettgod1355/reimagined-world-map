package com.hdmapreforged.route;

import static com.hdmapreforged.route.Tiles.CELL;

import java.io.*;
import java.util.*;
import lombok.*;

/**
 * Where a boat can sail: ground floor water in CELL×CELL blocks, open sea when mostly water (boats keep off rivers
 * and shores). Each block takes the hazard level of the nearest named sea (approximate near borders). Made once per
 * map version by the map server (the version's sea.bin), with its seas (sea_areas.tsv). Immutable.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class SeaMap
{
    static final String AREAS = RouteFiles.DIR + "sea_areas.tsv";
    /** No sea here. */
    static final byte NONE = -2;
    /** Sea no boat can cross yet. */
    static final byte CLOSED = -1;

    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class Area
    {
        public final String name;
        public final int x;
        public final int y;
        final String hazard;
        /** 0 for none, -1 when it cannot be crossed. */
        public final int level;
    }

    /** Per region, per 16×16 block: the level needed, NONE or CLOSED; null when no sea. */
    final byte[][] blocks = new byte[1 << 15][];
    final List<Area> areas;

    /**
     * Per region with sea, sea.bin holds its number (two bytes) and its 256 blocks. A map version without one (up to
     * cache-2727.40) has no sea: no sailing, but the planner still loads.
     */
    public static SeaMap load() throws IOException
    {
        var sea = new SeaMap(Collections.unmodifiableList(readAreas(RouteFiles.require(AREAS))));
        try (var in = new DataInputStream(RouteFiles.require(RouteFiles.DIR + "sea.bin")))
        {
            for (int high; (high = in.read()) >= 0; )
            {
                byte[] cells = new byte[256];
                int region = high << 8 | in.readUnsignedByte();
                in.readFully(cells);
                sea.blocks[region] = cells;
            }
        }
        catch (IOException | RuntimeException e)
        {
            // What was read.
        }
        return sea;
    }

    /** The seas {@code in} lists; closes it. */
    static List<Area> readAreas(InputStream in) throws IOException
    {
        var areas = new ArrayList<Area>();
        Pathfinder.rows(in, -1, c -> {
            // Blank lines are short rows too.
            if (c.length < 6)
            {
                return;
            }
            try
            {
                // Name, ocean (not used), x, y, hazard, level.
                areas.add(new Area(c[0], Integer.parseInt(c[2].trim()), Integer.parseInt(c[3].trim()), c[4],
                    Integer.parseInt(c[5].trim())));
            }
            catch (NumberFormatException e)
            {
                // Skip the row.
            }
        });
        return areas;
    }

    public Area nearest(int x, int y)
    {
        Area best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Area area : areas)
        {
            long dx = area.x - x;
            long dy = area.y - y;
            long d = dx * dx + dy * dy;
            if (d < bestDistance)
            {
                bestDistance = d;
                best = area;
            }
        }
        return best;
    }

    /** The level a block needs, NONE or CLOSED. */
    int level(int cellX, int cellY)
    {
        if (cellX < 0 || cellY < 0 || cellX >= 1 << 12 || cellY >= 1 << 12)
        {
            return NONE;
        }
        int region = (cellX >> 4) << 8 | (cellY >> 4);
        if (region >= 1 << 15)
        {
            return NONE;
        }
        var cells = blocks[region];
        return cells == null ? NONE : cells[(cellX & 15) << 4 | (cellY & 15)];
    }

    public boolean sailable(int cellX, int cellY, int sailingLevel)
    {
        int level = level(cellX, cellY);
        return level >= 0 && sailingLevel >= level;
    }

    /** The nearest sailable block within {@code radius} tiles, as a packed sea node, or -1. */
    public int nearestBlock(int x, int y, int radius, int sailingLevel)
    {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int cx = (x - radius) / CELL; cx <= (x + radius) / CELL; cx++)
        {
            for (int cy = (y - radius) / CELL; cy <= (y + radius) / CELL; cy++)
            {
                if (!sailable(cx, cy, sailingLevel))
                {
                    continue;
                }
                long dx = cx * CELL + CELL / 2 - x;
                long dy = cy * CELL + CELL / 2 - y;
                long d = dx * dx + dy * dy;
                if (d < bestDistance && d <= (long) radius * radius)
                {
                    bestDistance = d;
                    best = Tiles.sea(cx, cy);
                }
            }
        }
        return best;
    }
}
