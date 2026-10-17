package com.hdmapreforged.route;

import static com.hdmapreforged.route.Tiles.*;

import java.util.*;

/**
 * Walking the way the game walks: a breadth-first search over 128×128 tiles around the player with directions in a
 * fixed order, so the drawn route lies on the tiles the player really walks.
 */
public final class GameWalk
{
    static final int HALF = 64;
    /** Well inside the search area. */
    static final int PIECE = 48;
    static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
    static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};

    /** A route's walk as the game would take it; pieces the game cannot do as short keep the route's tiles. */
    public static int[] follow(CollisionMap map, int[] points)
    {
        if (points.length < 3 || isSea(points[0]))
        {
            return points;
        }
        int[] out = new int[points.length * 2];
        int n = 0;
        out[n++] = points[0];
        int i = 0;
        while (i < points.length - 1)
        {
            int j = i + 1;
            while (j + 1 < points.length && z(points[j + 1]) == z(points[i])
                && distance(points[i], points[j + 1]) <= PIECE)
            {
                j++;
            }
            var piece = z(points[j]) == z(points[i]) ? path(map, out[n - 1], points[j]) : null;
            if (piece == null || piece.length > j - i + 1 || newObstacle(map, piece, points, i, j))
            {
                piece = Arrays.copyOfRange(points, i, j + 1);
            }
            if (n + piece.length > out.length)
            {
                out = Arrays.copyOf(out, (n + piece.length) * 2);
            }
            System.arraycopy(piece, 1, out, n, piece.length - 1);
            n += piece.length - 1;
            i = j;
        }
        return Arrays.copyOf(out, n);
    }

    /** How far ahead one run pair (straight, then diagonal) is looked for. */
    static final int RUN = 64;

    /**
     * The walk in as few runs as the ground allows, as a player walks it: from each tile to the farthest one ahead that
     * one straight run and then one diagonal run reach in as many steps, so no time changes. That is the game's walk to
     * a clicked tile in the open: its search tries west, east, south, north before the diagonals, so the path goes
     * straight along the longer axis first, then diagonally; never diagonally first. Not through a door or onto an
     * obstacle the walk kept clear of.
     */
    public static int[] straighten(CollisionMap map, int[] points)
    {
        if (points.length < 3 || isSea(points[0]))
        {
            return points;
        }
        int[] out = new int[points.length];
        int n = 0;
        out[n++] = points[0];
        int i = 0;
        while (i < points.length - 1)
        {
            int[] best = null;
            int to = i + 1;
            for (int j = Math.min(points.length - 1, i + RUN); j > i + 1 && best == null; j--)
            {
                best = runs(map, points, i, j);
                to = j;
            }
            if (best == null)
            {
                out[n++] = points[i + 1];
                i++;
                continue;
            }
            System.arraycopy(best, 1, out, n, best.length - 1);
            n += best.length - 1;
            i = to;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * From {@code points[i]} to {@code points[j]} by one straight run and then one diagonal run, in exactly
     * {@code j - i} steps over walkable ground, crossing no door and stepping on no obstacle that {@code points[i..j]}
     * does not; else null.
     */
    static int[] runs(CollisionMap map, int[] points, int i, int j)
    {
        int from = points[i];
        int to = points[j];
        int z = z(from);
        int dx = x(to) - x(from);
        int dy = y(to) - y(from);
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (z(to) != z || steps != j - i)
        {
            return null;
        }
        int diagonal = Math.min(Math.abs(dx), Math.abs(dy));
        int sx = Integer.signum(dx);
        int sy = Integer.signum(dy);
        // The straight run goes along the longer axis.
        int ax = Math.abs(dx) > Math.abs(dy) ? sx : 0;
        int ay = Math.abs(dx) > Math.abs(dy) ? 0 : sy;
        int[] path = new int[steps + 1];
        path[0] = from;
        int x = x(from);
        int y = y(from);
        for (int k = 0; k < steps; k++)
        {
            boolean diagonalStep = k >= steps - diagonal;
            int mx = diagonalStep ? sx : ax;
            int my = diagonalStep ? sy : ay;
            if (!map.canStep(x, y, z, mx, my) || (mx == 0 || my == 0) && map.door(x, y, z, mx, my)
                && !crossed(points, i, j, x, y, mx, my))
            {
                return null;
            }
            x += mx;
            y += my;
            path[k + 1] = pack(x, y, z);
            if (newObstacle(map, new int[]{path[k + 1]}, points, i, j))
            {
                return null;
            }
        }
        return path;
    }

    /** Whether {@code points[i..j]} steps from (x, y) by (mx, my) itself (so a door it opens may be crossed). */
    static boolean crossed(int[] points, int i, int j, int x, int y, int mx, int my)
    {
        for (int k = i; k < j; k++)
        {
            if (x(points[k]) == x && y(points[k]) == y && x(points[k + 1]) == x + mx
                && y(points[k + 1]) == y + my)
            {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code piece} crosses an obstacle (vines, rocks) that {@code points[i..j]} kept clear of. */
    static boolean newObstacle(CollisionMap map, int[] piece, int[] points, int i, int j)
    {
        for (int t : piece)
        {
            if (map.isObstacle(x(t), y(t), t)
                && Arrays.stream(points, i, j + 1).noneMatch(p -> p == t))
            {
                return true;
            }
        }
        return false;
    }

    /** Both ends included, or null. */
    public static int[] path(CollisionMap map, int from, int to)
    {
        return path(map, from, new int[]{to});
    }

    /**
     * The game's walk to the first of {@code goals} its search reaches (of a bank chest's sides, the one the game walks
     * to when it is clicked), both ends included; null when none is reached.
     */
    public static int[] path(CollisionMap map, int from, int[] goals)
    {
        int z = z(from);
        int ox = x(from) - HALF;
        int oy = y(from) - HALF;
        int size = HALF * 2;
        boolean[] wanted = new boolean[size * size];
        for (int to : goals)
        {
            int tx = x(to) - ox;
            int ty = y(to) - oy;
            if (z(to) == z && tx >= 0 && ty >= 0 && tx < size && ty < size)
            {
                wanted[ty * size + tx] = true;
            }
        }
        // Direction each tile was reached from; -1 not reached.
        byte[] via = new byte[size * size];
        Arrays.fill(via, (byte) -1);
        int start = HALF * size + HALF;
        via[start] = 8;
        var queue = new ArrayDeque<Integer>();
        queue.add(start);
        int goal = wanted[start] ? start : -1;
        while (!queue.isEmpty() && goal < 0)
        {
            int cell = queue.poll();
            int cx = cell % size;
            int cy = cell / size;
            for (int d = 0; d < 8; d++)
            {
                int nx = cx + DX[d];
                int ny = cy + DY[d];
                if (nx < 0 || ny < 0 || nx >= size || ny >= size || via[ny * size + nx] >= 0)
                {
                    continue;
                }
                if (goal < 0 && map.canStep(cx + ox, cy + oy, z, DX[d], DY[d]))
                {
                    via[ny * size + nx] = (byte) d;
                    queue.add(ny * size + nx);
                    goal = wanted[ny * size + nx] ? ny * size + nx : -1;
                }
            }
        }
        if (goal < 0)
        {
            return null;
        }
        // Back from the goal, each tile put before the one after it.
        var cells = new ArrayDeque<Integer>();
        for (int cell = goal; cell != start; )
        {
            cells.push(cell);
            int d = via[cell];
            cell = (cell / size - DY[d]) * size + (cell % size - DX[d]);
        }
        int[] path = new int[cells.size() + 1];
        path[0] = from;
        int k = 1;
        for (int c : cells)
        {
            path[k++] = pack(c % size + ox, c / size + oy, z);
        }
        return path;
    }
}
