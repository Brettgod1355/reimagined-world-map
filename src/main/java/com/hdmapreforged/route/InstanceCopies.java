package com.hdmapreforged.route;

import java.util.*;

/** Instances whose template copies a place on the map ({@code route/instance_copies.tsv}): their tiles as that place's. */
public final class InstanceCopies
{
    static final RouteFiles.Table<List<int[]>> COPIES = new RouteFiles.Table<>(InstanceCopies::load);

    static List<int[]> load()
    {
        return Pathfinder.table("/com/hdmapreforged/route/instance_copies.tsv", parts -> {
            var min = Pathfinder.ints(parts[0]);
            var max = Pathfinder.ints(parts[1]);
            var by = Pathfinder.ints(parts[2]);
            return new int[]{min[0], min[1], max[0], max[1], by[0], by[1]};
        });
    }

    /** The place's tile for a template tile ({@code x y plane}), or null when no known copy holds it. */
    public static int[] place(int x, int y, int plane)
    {
        for (var c : COPIES.get())
        {
            if (x >= c[0] && y >= c[1] && x <= c[2] && y <= c[3])
            {
                return new int[]{x - c[4], y - c[5], plane};
            }
        }
        return null;
    }

    /** The template tile for a tile of a copied place, or null when no known copy holds it: what the game draws there. */
    public static int[] template(int x, int y, int plane)
    {
        for (var c : COPIES.get())
        {
            if (x >= c[0] - c[4] && y >= c[1] - c[5] && x <= c[2] - c[4] && y <= c[3] - c[5])
            {
                return new int[]{x + c[4], y + c[5], plane};
            }
        }
        return null;
    }

    /** Whether a tile of the map is the place of a known copy (the player in that instance, as drawn and routed). */
    public static boolean isPlace(int x, int y)
    {
        return template(x, y, 0) != null;
    }
}
