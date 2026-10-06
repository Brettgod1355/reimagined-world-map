package com.hdmapreforged.route;

import java.io.*;
import java.util.*;
import lombok.*;

/**
 * Where a boat can sail: ground floor water in CELL×CELL blocks, open sea when mostly water (boats keep off rivers
 * and shores). Each block takes the hazard level of the nearest named wiki sea (approximate near borders). Immutable.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class SeaMap
{
    public static final String AREAS = "/com/hdmapreforged/route/sea_areas.tsv";
    /** Water tiles a block needs, of CELL * CELL. */
    static final int MIN_WATER = 10;
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
        public final String hazard;
        /** 0 for none, -1 when it cannot be crossed. */
        public final int level;
    }

    /** Per region, per 16×16 block: the level needed, NONE or CLOSED; null when no sea. */
    private final byte[][] blocks = new byte[1 << 15][];
    private final List<Area> areas;

    public static SeaMap build(CollisionMap collision) throws IOException
    {
        InputStream in = RouteFiles.open(AREAS);
        if (in == null)
        {
            throw new IOException("Missing " + AREAS);
        }
        return build(collision, readAreas(in));
    }

    /** The seas {@code in} lists; closes it. */
    static List<Area> readAreas(InputStream in) throws IOException
    {
        List<Area> areas = new ArrayList<>();
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

    public static SeaMap build(CollisionMap collision, List<Area> areas)
    {
        SeaMap sea = new SeaMap(Collections.unmodifiableList(areas));
        for (int region = 0; region < 1 << 15; region++)
        {
            if (!collision.hasWater(region))
            {
                continue;
            }
            int baseX = (region >> 8) << 6;
            int baseY = (region & 255) << 6;
            byte[] cells = null;
            for (int cx = 0; cx < 64 / Tiles.CELL; cx++)
            {
                for (int cy = 0; cy < 64 / Tiles.CELL; cy++)
                {
                    int count = 0;
                    for (int dx = 0; dx < Tiles.CELL; dx++)
                    {
                        for (int dy = 0; dy < Tiles.CELL; dy++)
                        {
                            if (collision.water(baseX + cx * Tiles.CELL + dx, baseY + cy * Tiles.CELL + dy))
                            {
                                count++;
                            }
                        }
                    }
                    if (count < MIN_WATER)
                    {
                        continue;
                    }
                    if (cells == null)
                    {
                        cells = new byte[256];
                        Arrays.fill(cells, NONE);
                    }
                    Area area = sea.nearest(baseX + cx * Tiles.CELL + 2, baseY + cy * Tiles.CELL + 2);
                    cells[cx << 4 | cy] = area == null ? 0 : (byte) Math.max(CLOSED, Math.min(120, area.level));
                }
            }
            sea.blocks[region] = cells;
        }
        return sea;
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
        byte[] cells = blocks[region];
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
        for (int cx = (x - radius) / Tiles.CELL; cx <= (x + radius) / Tiles.CELL; cx++)
        {
            for (int cy = (y - radius) / Tiles.CELL; cy <= (y + radius) / Tiles.CELL; cy++)
            {
                if (!sailable(cx, cy, sailingLevel))
                {
                    continue;
                }
                long dx = cx * Tiles.CELL + Tiles.CELL / 2 - x;
                long dy = cy * Tiles.CELL + Tiles.CELL / 2 - y;
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
