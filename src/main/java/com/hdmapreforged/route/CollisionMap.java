package com.hdmapreforged.route;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;

/**
 * Where one can walk, from the bundled collision.zip: per region and floor, bitsets of walkable tiles and of walls and
 * doors on the north and east edges, plus ground-floor water. Immutable after loading but for one's own house
 * ({@link #setHouse}, replaced whole), so any thread may read it.
 */
public final class CollisionMap
{
    public static final String RESOURCE = "/com/hdmapreforged/route/collision.zip";
    static final int MAGIC = 0x48444d43;
    static final int WALK = 0;
    static final int WALL_N = 64;
    static final int WALL_E = 128;
    static final int DOOR_N = 192;
    static final int DOOR_E = 256;
    static final int LAYERS = 320;
    static final int MAX_BIN_BYTES = 64 * 1024 * 1024;
    static final int MAX_TABLE_BYTES = 16 * 1024 * 1024;

    /** By {@code region * 4 + plane}: five bitsets of 64 longs, or null. */
    final long[][] floors = new long[1 << 18][];
    final long[][] water = new long[1 << 16][];
    final List<Transition> transitions;
    /** By packed tile: "Chop-down Vines (bring an axe)". */
    final Map<Integer, String> obstacles;
    final boolean[] obstacleRegions = new boolean[1 << 16];
    /** One's own house, where no map of the game lies; replaced whole. */
    volatile HousePlan house;

    CollisionMap(List<Transition> transitions, Map<Integer, String> obstacles)
    {
        this.transitions = transitions;
        this.obstacles = obstacles;
        for (int node : obstacles.keySet())
        {
            obstacleRegions[region(Tiles.x(node), Tiles.y(node))] = true;
        }
    }

    public static CollisionMap load() throws IOException
    {
        InputStream in = RouteFiles.open(RESOURCE);
        if (in == null)
        {
            throw new IOException("Missing " + RESOURCE);
        }
        try (in)
        {
            return read(in);
        }
    }

    public static CollisionMap read(InputStream zipped) throws IOException
    {
        byte[] bin = null;
        var transitions = new ArrayList<Transition>();
        var obstacles = new HashMap<Integer, String>();
        var zip = new ZipInputStream(zipped);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null)
        {
            if ("collision.bin".equals(entry.getName()))
            {
                bin = RouteFiles.read(zip, MAX_BIN_BYTES, "collision.bin");
            }
            else if ("transitions.tsv".equals(entry.getName()))
            {
                lines(RouteFiles.read(zip, MAX_TABLE_BYTES, "transitions.tsv"), line -> {
                    Transition t = Transition.parse(line);
                    if (t != null)
                    {
                        transitions.add(t);
                    }
                });
            }
            else if ("obstacles.tsv".equals(entry.getName()))
            {
                lines(RouteFiles.read(zip, MAX_TABLE_BYTES, "obstacles.tsv"), line -> obstacle(obstacles, line));
            }
        }
        if (bin == null)
        {
            throw new IOException("No collision.bin");
        }
        var map = new CollisionMap(Collections.unmodifiableList(transitions), obstacles);
        map.decode(bin);
        return map;
    }

    static void lines(byte[] bytes, Consumer<String> each) throws IOException
    {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null)
        {
            each.accept(line);
        }
    }

    void decode(byte[] bin) throws IOException
    {
        var in = new DataInputStream(new ByteArrayInputStream(bin));
        if (in.readInt() != MAGIC || in.readInt() != 1)
        {
            throw new IOException("Unknown collision data");
        }
        int count = in.readInt();
        if (count < 0 || count > 1 << 16)
        {
            throw new IOException("Bad region count " + count);
        }
        for (int i = 0; i < count; i++)
        {
            int region = in.readUnsignedShort();
            int mask = in.readUnsignedByte();
            for (int z = 0; z < 4; z++)
            {
                if ((mask & 1 << z) != 0)
                {
                    floors[region << 2 | z] = longs(in, LAYERS);
                }
            }
            if ((mask & 16) != 0)
            {
                water[region] = longs(in, 64);
            }
        }
    }

    static long[] longs(DataInputStream in, int count) throws IOException
    {
        long[] longs = new long[count];
        for (int j = 0; j < count; j++)
        {
            longs[j] = in.readLong();
        }
        return longs;
    }

    /** The region of a tile, or -1 off the world. */
    static int region(int x, int y)
    {
        return (x | y) >>> 14 == 0 ? (x >> 6) << 8 | (y >> 6) : -1;
    }

    /** "x y plane <tab> name <tab> action <tab> what it takes"; bad lines are skipped. */
    static void obstacle(Map<Integer, String> obstacles, String line)
    {
        var parts = line.split("\t");
        if (line.startsWith("#") || parts.length < 4)
        {
            return;
        }
        int at = Tiles.parse(parts[0]);
        if (at < 0)
        {
            return;
        }
        String action = parts[2].isEmpty() ? parts[2] : Character.toUpperCase(parts[2].charAt(0)) + parts[2].substring(1);
        obstacles.put(at, action + " " + parts[1] + " (bring " + parts[3] + ")");
    }

    public List<Transition> transitions()
    {
        return transitions;
    }

    public String obstacle(int node)
    {
        return isObstacle(Tiles.x(node), Tiles.y(node), node) ? obstacles.get(node) : null;
    }

    public boolean isObstacle(int x, int y, int node)
    {
        int region = region(x, y);
        return region >= 0 && obstacleRegions[region] && obstacles.containsKey(node);
    }

    long[] layers(int x, int y, int z)
    {
        int region = region(x, y);
        var found = region < 0 || z < 0 || z > 3 ? null : floors[region << 2 | z];
        var plan = found == null && region >= 0 && z >= 0 && z <= 3 ? house : null;
        return plan != null ? plan.layers(region, z) : found;
    }

    public void setHouse(HousePlan plan)
    {
        house = plan;
    }

    public HousePlan house()
    {
        return house;
    }

    static boolean bit(long[] layers, int offset, int x, int y)
    {
        int i = (x & 63) << 6 | (y & 63);
        return (layers[offset + (i >> 6)] & 1L << (i & 63)) != 0;
    }

    public boolean walkable(int x, int y, int z)
    {
        return edge(x, y, z, WALK);
    }

    public boolean hasWater(int region)
    {
        return region >= 0 && region < 1 << 16 && water[region] != null;
    }

    public boolean water(int x, int y)
    {
        int region = region(x, y);
        return region >= 0 && water[region] != null && bit(water[region], 0, x, y);
    }

    boolean edge(int x, int y, int z, int offset)
    {
        var layers = layers(x, y, z);
        return layers != null && bit(layers, offset, x, y);
    }

    public boolean canStep(int x, int y, int z, int dx, int dy)
    {
        int nx = x + dx;
        int ny = y + dy;
        if (!walkable(nx, ny, z))
        {
            return false;
        }
        if (dx != 0 && dy != 0)
        {
            // Diagonal: both straight routes around the corner must be open, and no door on the way.
            return walkable(x + dx, y, z) && walkable(x, y + dy, z) && clear(x, y, z, dx, 0) && clear(x + dx, y, z, 0, dy)
                && clear(x, y, z, 0, dy) && clear(x, y + dy, z, dx, 0);
        }
        return straightOpen(x, y, z, dx, dy);
    }

    boolean straightOpen(int x, int y, int z, int dx, int dy)
    {
        return !crosses(x, y, z, dx, dy, WALL_N, WALL_E);
    }

    boolean clear(int x, int y, int z, int dx, int dy)
    {
        return straightOpen(x, y, z, dx, dy) && !door(x, y, z, dx, dy);
    }

    public boolean door(int x, int y, int z, int dx, int dy)
    {
        return (dx == 1 || dx == -1 || dy == 1 || dy == -1) && crosses(x, y, z, dx, dy, DOOR_N, DOOR_E);
    }

    boolean crosses(int x, int y, int z, int dx, int dy, int north, int east)
    {
        return dx == 1 ? edge(x, y, z, east) : dx == -1 ? edge(x - 1, y, z, east)
            : dy == 1 ? edge(x, y, z, north) : edge(x, y - 1, z, north);
    }

    /** Nearest walkable tile within {@code radius} (Chebyshev rings, then straight distance), packed, or -1. */
    public int nearestWalkable(int x, int y, int z, int radius)
    {
        return nearestWalkable(x, y, z, radius, x, y);
    }

    /** Ties go farthest from {@code (awayX, awayY)}: a shortcut's near side, not a pocket between its stones. */
    public int nearestWalkable(int x, int y, int z, int radius, int awayX, int awayY)
    {
        return nearestWalkable(x, y, z, radius, awayX, awayY, null);
    }

    /** The same, but tiles {@code avoid} names (a shut-in pocket) only when no other is within reach. */
    public int nearestWalkable(int x, int y, int z, int radius, int awayX, int awayY, IntPredicate avoid)
    {
        int plain = nearest(x, y, z, radius, awayX, awayY, null);
        if (avoid == null || plain < 0 || !avoid.test(plain))
        {
            return plain;
        }
        int open = nearest(x, y, z, radius, awayX, awayY, avoid);
        return open >= 0 ? open : plain;
    }

    int nearest(int x, int y, int z, int radius, int awayX, int awayY, IntPredicate avoid)
    {
        for (int r = 0; r <= radius; r++)
        {
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dy = -r; dy <= r; dy++)
                {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !walkable(x + dx, y + dy, z)
                        || avoid != null && avoid.test(Tiles.pack(x + dx, y + dy, z)))
                    {
                        continue;
                    }
                    double away = (x + dx - awayX) * (double) (x + dx - awayX) + (y + dy - awayY) * (double) (y + dy - awayY);
                    double d = (dx * dx + dy * dy) * 1e6 - away;
                    if (d < bestDistance)
                    {
                        bestDistance = d;
                        best = Tiles.pack(x + dx, y + dy, z);
                    }
                }
            }
            if (best >= 0)
            {
                return best;
            }
        }
        return -1;
    }
}
