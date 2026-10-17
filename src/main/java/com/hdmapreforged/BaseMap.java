package com.hdmapreforged;

import lombok.*;

/**
 * One of the maps, in world tile coordinates: the game's own world maps, the map of everything, and our maps of the
 * dungeons the game's map lacks, which show the map of everything's tiles ({@code tiles}).
 */
@RequiredArgsConstructor
final class BaseMap
{
    static final int SURFACE = 0;
    static final int FULL = -1;

    final int id;
    final String name;
    final int minX;
    final int minY;
    final int maxX;
    final int maxY;
    final int centerX;
    final int centerY;
    /** The map whose tiles draw this one: itself, or {@link #FULL}. */
    final int tiles;
    /** The map list it is of (set by it): its region table tells on which floor it draws some ground. */
    BaseMaps maps;

    BaseMap(int id, String name, int minX, int minY, int maxX, int maxY, int centerX, int centerY)
    {
        this(id, name, minX, minY, maxX, maxY, centerX, centerY, id);
    }

    boolean contains(int x, int y)
    {
        return x >= minX && x < maxX && y >= minY && y < maxY;
    }

    long area()
    {
        return (long) (maxX - minX) * (maxY - minY);
    }

    public String toString()
    {
        return name;
    }
}
