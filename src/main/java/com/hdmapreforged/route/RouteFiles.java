package com.hdmapreforged.route;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import lombok.*;

/**
 * The route planner's tables (passages, gates, the collision map, ...): a map version's route.zip from the map server,
 * so they are fixed without a plugin update; before one is given (tests, development tools) the classpath.
 */
public final class RouteFiles
{
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_FILES = 100;
    private static volatile Map<String, byte[]> files;
    /** Counts the route.zips used, so tables read from them are read again. */
    private static volatile int generation;

    private RouteFiles()
    {
    }

    /** Uses a map version's route.zip: its files by name ("links.tsv", "collision.zip"). */
    public static void use(byte[] zip) throws IOException
    {
        Map<String, byte[]> read = new HashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)))
        {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; )
            {
                if (!entry.isDirectory())
                {
                    byte[] bytes = read(in, MAX_FILE_BYTES, entry.getName());
                    total += bytes.length;
                    if (total > MAX_TOTAL_BYTES || read.size() >= MAX_FILES)
                    {
                        throw new IOException("Route data too large");
                    }
                    read.put(entry.getName().substring(entry.getName().lastIndexOf('/') + 1), bytes);
                }
            }
        }
        files = read;
        generation++;
    }

    /** The rest of a zip entry; more than {@code max} bytes is an error, so a small zip never fills the memory. */
    static byte[] read(InputStream in, int max, String name) throws IOException
    {
        byte[] bytes = in.readNBytes(max + 1);
        if (bytes.length > max)
        {
            throw new IOException("Route file too large: " + name);
        }
        return bytes;
    }

    /** Whether the tables can be read: a route.zip is in use, or the classpath has them (tests, development tools). */
    public static boolean available()
    {
        if (files != null)
        {
            return true;
        }
        try (InputStream in = RouteFiles.class.getResourceAsStream(CollisionMap.RESOURCE))
        {
            return in != null;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /** A table by its resource path ("/com/hdmapreforged/route/links.tsv"); null when there is none. */
    public static InputStream open(String resource)
    {
        Map<String, byte[]> given = files;
        if (given == null)
        {
            return RouteFiles.class.getResourceAsStream(resource);
        }
        byte[] bytes = given.get(resource.substring(resource.lastIndexOf('/') + 1));
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    /** What is read from a table, read again once another route.zip is used. */
    @RequiredArgsConstructor
    public static final class Table<T>
    {
        private final Supplier<T> read;
        private int readAt = -1;
        private T value;

        public synchronized T get()
        {
            int now = generation;
            if (readAt != now)
            {
                value = read.get();
                readAt = now;
            }
            return value;
        }
    }
}
