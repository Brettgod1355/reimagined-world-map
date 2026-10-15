package com.hdmapreforged.route;

import java.util.*;

/** How far a dungeon, cave or room reaches on its floor, to fit the map to the dungeon one enters. */
public final class WalkableArea
{
    WalkableArea()
    {
    }

    /** {minX, minY, maxX, maxY} of the tiles walkable from a point, or null when none or over {@code limit} tiles. */
    public static int[] bounds(CollisionMap map, int x, int y, int z, int limit)
    {
        int start = map.walkable(x, y, z) ? Tiles.pack(x, y, z) : map.nearestWalkable(x, y, z, 4);
        if (start < 0)
        {
            return null;
        }
        BitSet seen = new BitSet();
        // Dungeons never span more than this.
        int size = 1024;
        int ox = Tiles.x(start) - size / 2;
        int oy = Tiles.y(start) - size / 2;
        var open = new ArrayDeque<int[]>();
        open.add(new int[]{Tiles.x(start), Tiles.y(start)});
        seen.set((Tiles.x(start) - ox) * size + (Tiles.y(start) - oy));
        int[] b = {Tiles.x(start), Tiles.y(start), Tiles.x(start), Tiles.y(start)};
        int count = 0;
        while (!open.isEmpty())
        {
            var p = open.poll();
            if (++count > limit)
            {
                return null;
            }
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.max(b[2], p[0]);
            b[3] = Math.max(b[3], p[1]);
            for (int d = 0; d < 8; d++)
            {
                int nx = p[0] + Pathfinder.DX[d];
                int ny = p[1] + Pathfinder.DY[d];
                int wx = nx - ox;
                int wy = ny - oy;
                if (wx < 0 || wy < 0 || wx >= size || wy >= size || seen.get(wx * size + wy)
                    || !map.canStep(p[0], p[1], z, Pathfinder.DX[d], Pathfinder.DY[d]))
                {
                    continue;
                }
                seen.set(wx * size + wy);
                open.add(new int[]{nx, ny});
            }
        }
        return b;
    }
}
