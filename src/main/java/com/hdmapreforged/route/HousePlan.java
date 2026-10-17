package com.hdmapreforged.route;

import static com.hdmapreforged.route.Tiles.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * One's own house as last seen there: where one can walk and what stands where. Its tiles are at {@link #X},
 * {@link #Y} plus the house's own place (its south-west room at chunk 2, wherever the game loads it), where no map of
 * the game lies, so routes walk through it like anywhere else ({@link CollisionMap#setHouse}); the surface map draws
 * it at {@link #DRAWN_X}, {@link #DRAWN_Y}. Immutable.
 */
public final class HousePlan
{
    public static final int X = 12800;
    public static final int Y = 12800;
    /** The game's scene, 104 tiles square: the house lies within it. */
    public static final int SIZE = 104;
    /** Where the world map draws it: the surface map's empty west edge. */
    public static final int DRAWN_X = 896;
    public static final int DRAWN_Y = 3000;
    static final int TILES = SIZE * SIZE;

    /** Per floor, per tile (x * SIZE + y): 1 walkable, 2 wall north, 4 wall east, 8 door north, 16 door east. */
    final byte[][] tiles;
    /** What stands where (by node): the portals, jewellery box, stairs and such, by their names in the game. */
    public final Map<Integer, String> things;
    /** Where one arrives: north of the exit portal. */
    public final int arrival;
    /** Per region of the plan (2 × 2) and floor: {@link CollisionMap}'s layers. */
    final long[][] layers = new long[16][];

    public HousePlan(byte[][] tiles, Map<Integer, String> things, int arrival)
    {
        this.tiles = tiles;
        this.things = Collections.unmodifiableMap(new LinkedHashMap<>(things));
        this.arrival = arrival;
        for (int z = 0; z < 4; z++)
        {
            for (int i = 0; i < TILES; i++)
            {
                int x = i / SIZE;
                int y = i % SIZE;
                int bit = (x & 63) << 6 | (y & 63);
                for (int k = 0; k < 5; k++)
                {
                    if ((tiles[z][i] & 1 << k) != 0)
                    {
                        int at = ((x >> 6) * 2 + (y >> 6)) * 4 + z;
                        if (layers[at] == null)
                        {
                            layers[at] = new long[320];
                        }
                        layers[at][k * 64 + (bit >> 6)] |= 1L << (bit & 63);
                    }
                }
            }
        }
    }

    /** Only floor one can walk to from these nodes stays walkable: grass beyond the walls is not the house. */
    public static void keepReachable(byte[][] tiles, List<Integer> from)
    {
        boolean[][] reached = new boolean[4][TILES];
        var queue = new ArrayDeque<Integer>();
        for (int node : from)
        {
            reach(tiles, reached, queue, contains(node) ? (x(node) - X) * SIZE + y(node) - Y : -1, z(node));
        }
        while (!queue.isEmpty())
        {
            int at = queue.poll();
            for (int n : steps(tiles[at / TILES], at % TILES))
            {
                reach(tiles, reached, queue, n, at / TILES);
            }
        }
        for (int z = 0; z < 4; z++)
        {
            for (int i = 0; i < TILES; i++)
            {
                tiles[z][i] &= reached[z][i] ? ~0 : ~1;
            }
        }
    }

    static void reach(byte[][] tiles, boolean[][] reached, ArrayDeque<Integer> queue, int i, int z)
    {
        if (i >= 0 && z >= 0 && z < 4 && !reached[z][i] && (tiles[z][i] & 1) != 0)
        {
            reached[z][i] = true;
            queue.add(z * TILES + i);
        }
    }

    /**
     * Where one stands to use a thing: the nearest walkable tile within 3 steps of it, through the thing itself but
     * not through a wall (a portal against a wall is used from its room, not the next); -1 none.
     */
    public int approach(int node)
    {
        int z = z(node);
        int from = (x(node) - X) * SIZE + y(node) - Y;
        var ring = List.of(from);
        var seen = new HashSet<Integer>(ring);
        for (int step = 0; step <= 3 && contains(node) && z >= 0 && z < 4; step++)
        {
            var next = new ArrayList<Integer>();
            int best = -1;
            for (int i : ring)
            {
                // Of the nearest free tiles one with room beyond it: the front, not a gap behind (a box before a wall).
                if ((tiles[z][i] & 1) != 0 && (best < 0 || !beyond(z, from, best) && beyond(z, from, i)))
                {
                    best = i;
                }
                for (int n : steps(tiles[z], i))
                {
                    if (n >= 0 && seen.add(n))
                    {
                        next.add(n);
                    }
                }
            }
            if (best >= 0)
            {
                return pack(X + best / SIZE, Y + best % SIZE, z);
            }
            ring = next;
        }
        return -1;
    }

    /**
     * The tiles one steps to from {@code i} (north, east, south, west), -1 where a wall is: walls stand on a tile's
     * north and east edges, and doors let one through.
     */
    static int[] steps(byte[] floor, int i)
    {
        int x = i / SIZE;
        int y = i % SIZE;
        return new int[]{y < SIZE - 1 && (floor[i] & 2) == 0 ? i + 1 : -1, x < SIZE - 1 && (floor[i] & 4) == 0 ? i + SIZE
            : -1, y > 0 && (floor[i - 1] & 2) == 0 ? i - 1 : -1, x > 0 && (floor[i - SIZE] & 4) == 0 ? i - SIZE : -1};
    }

    /** Whether one can step on from {@code i}, away from {@code from} straight (north, east, south or west). */
    boolean beyond(int z, int from, int i)
    {
        int dx = Integer.signum(i / SIZE - from / SIZE);
        int dy = Integer.signum(i % SIZE - from % SIZE);
        int j = i + dx * SIZE + dy;
        boolean edge = i / SIZE + dx < 0 || i / SIZE + dx >= SIZE || i % SIZE + dy < 0 || i % SIZE + dy >= SIZE;
        return (dx == 0) != (dy == 0) && !edge && (tiles[z][j] & 1) != 0
            && (tiles[z][dy > 0 || dx > 0 ? i : j] & (dy != 0 ? 2 : 4)) == 0;
    }

    public static int node(int sceneX, int sceneY, int plane)
    {
        return pack(X + sceneX, Y + sceneY, plane);
    }

    public static boolean contains(int node)
    {
        return contains(x(node), y(node));
    }

    public static boolean contains(int x, int y)
    {
        return x >= X && x < X + SIZE && y >= Y && y < Y + SIZE;
    }

    long[] layers(int region, int z)
    {
        int dx = (region >> 8) - (X >> 6);
        int dy = (region & 255) - (Y >> 6);
        return dx < 0 || dx > 1 || dy < 0 || dy > 1 ? null : layers[(dx * 2 + dy) * 4 + z];
    }

    /** As kept per account: the tiles deflated in Base64, the arrival, then per thing "node TAB name". */
    public String encode()
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes))
        {
            for (var floor : tiles)
            {
                out.write(floor);
            }
        }
        catch (IOException e)
        {
            return null;
        }
        StringBuilder text = new StringBuilder(Base64.getEncoder().encodeToString(bytes.toByteArray()))
            .append('\n').append(arrival);
        things.forEach((node, name) -> text.append('\n').append(node).append('\t').append(name));
        return text.toString();
    }

    /** Null when unreadable; things off the plan are left out. */
    public static HousePlan decode(String text)
    {
        var lines = text.split("\n");
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(lines[0]))))
        {
            var all = in.readNBytes(4 * TILES + 1);
            int arrival = Integer.parseInt(lines[1]);
            if (all.length != 4 * TILES || !contains(arrival))
            {
                return null;
            }
            byte[][] tiles = new byte[4][];
            for (int z = 0; z < 4; z++)
            {
                tiles[z] = Arrays.copyOfRange(all, z * TILES, (z + 1) * TILES);
            }
            var things = new LinkedHashMap<Integer, String>();
            for (int i = 2; i < lines.length; i++)
            {
                var parts = lines[i].split("\t", 2);
                int node = Integer.parseInt(parts[0]);
                if (parts.length == 2 && contains(node) && parts[1].length() <= 2000)
                {
                    things.put(node, parts[1]);
                }
            }
            return new HousePlan(tiles, things, arrival);
        }
        catch (RuntimeException | IOException e)
        {
            return null;
        }
    }
}
