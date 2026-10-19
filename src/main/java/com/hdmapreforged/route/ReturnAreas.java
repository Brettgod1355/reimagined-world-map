package com.hdmapreforged.route;

import java.util.*;
import lombok.*;

/**
 * Areas whose way out leads back to where one came from (return_areas.tsv): the Rune essence mine's portals return one to
 * the wizard who sent one there, the Shipyard's portal to the shipwright. Read once. Immutable.
 */
public final class ReturnAreas
{
    public static final String RESOURCE = RouteFiles.DIR + "return_areas.tsv";

    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class Area
    {
        public final String name;
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;
        final int plane;
        /** The exit objects' tiles, packed. */
        public final int[] exits;
        /** The step's name ("Use Portal"). */
        public final String exitName;

        public boolean contains(int node)
        {
            return node >= 0 && !Tiles.isSea(node) && Tiles.z(node) == plane && Tiles.x(node) >= minX
                && Tiles.x(node) <= maxX && Tiles.y(node) >= minY && Tiles.y(node) <= maxY;
        }
    }

    static final RouteFiles.Table<List<Area>> AREAS = new RouteFiles.Table<>(ReturnAreas::read);

    /** The area holding the tile, or null. */
    public static Area at(int node)
    {
        return AREAS.get().stream().filter(area -> area.contains(node)).findFirst().orElse(null);
    }

    static List<Area> read()
    {
        return Pathfinder.table(RESOURCE, cells -> {
            var box = Pathfinder.ints(cells[1]);
            var tiles = cells[2].trim().split(";");
            int[] exits = new int[tiles.length];
            for (int i = 0; i < tiles.length; i++)
            {
                exits[i] = Tiles.parse(tiles[i].trim());
            }
            return new Area(cells[0].trim(), box[0], box[1], box[2], box[3], box[4], exits, cells[3].trim());
        });
    }
}
