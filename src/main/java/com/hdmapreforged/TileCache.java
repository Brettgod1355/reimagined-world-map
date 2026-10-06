package com.hdmapreforged;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.util.*;
import okhttp3.*;

/**
 * Map tiles from our map server, in memory, optionally on disk, downloaded a few at a time. Each frame queues tiles farthest first
 * and loads at frame end, so the middle loads first; tiles scrolled away before their turn are dropped.
 */
@Slf4j
@RequiredArgsConstructor
final class TileCache
{
    static final int TILE_SIZE = 256;
    static final int MIN_ZOOM = -3;
    static final int MAX_ZOOM = 3;
    /** OkHttp itself still caps requests at 5 per host, shared with the rest of RuneLite. */
    private static final int MAX_IN_FLIGHT = 10;
    /** A tile is well under 200 KB. */
    static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    /** Older half-written files were left by a crash; newer ones may still be being written. */
    private static final long PART_STALE_MS = 10 * 60 * 1000L;
    private static final long RETRY_AFTER_MS = 30_000;
    private static final long MB = 1024 * 1024;
    /**
     * 128 MB: past it the views draw a coarser level, a quarter of the tiles (a 4K window's upper floor would need 2,000
     * tiles, 500 MB, just above a whole zoom). A higher memory setting raises it.
     */
    static final int MAX_TILES = 512;
    /** Kept while no map is open, for the next opening. */
    static final int CLOSED_TILES = 64;

    @RequiredArgsConstructor
    @EqualsAndHashCode
    static final class Key
    {
        final int map;
        final int zoom;
        final int plane;
        final int x;
        final int y;

        String path()
        {
            return map + "/" + zoom + "/" + plane + "_" + x + "_" + y + ".png";
        }
    }

    private final OkHttpClient http;
    private final ExecutorService io;
    private final Filepath cacheRoot;
    private final Runnable onLoaded;

    private final LinkedHashMap<Key, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Key> missing = new HashSet<>();
    private final Map<Key, Long> failed = new HashMap<>();
    /** Previous-version tiles shown while this version's are fetched. */
    private final Set<Key> stale = new HashSet<>();
    private final Deque<Key> queue = new ArrayDeque<>();
    private final Set<Key> queued = new HashSet<>();
    private final Set<Key> loading = new HashSet<>();
    /** Downloads under way: cancelled when the cache is cleared (the plugin stops). */
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    /** Requests still running, of any generation: {@link #clear} empties {@code loading} but not the network. */
    private int inFlight;
    /** Bytes on disk as last counted, plus writes since; a trim runs once it passes the limit. */
    private final AtomicLong diskBytes = new AtomicLong();
    private final AtomicBoolean trimQueued = new AtomicBoolean();
    private boolean configured;
    private volatile String version;
    private volatile boolean diskCache;
    private volatile int memoryLimit = 256;
    private volatile int diskLimitMb = 1000;
    /** What each open view needs; the memory cache never shrinks below their sum, or tiles in view would reload. */
    private final Map<Object, Integer> needs = new HashMap<>();
    private int frameNeed;
    private int generation;
    /** Old versions are removed once a tile of an official version downloads, so a mistyped version never wipes the cache. */
    private boolean cleanupPending;
    /** Previous version on disk, shown while the new one downloads; each old tile is removed once replaced. */
    volatile String fallbackVersion;
    /** Only then are the fallback's tiles removed: a manual version or the start-up stand-in must not wear it down. */
    private volatile boolean official;

    static int tileIndex(double world, int zoom)
    {
        return (int) Math.floor(world * Math.pow(2, zoom) / TILE_SIZE);
    }

    static double worldPerTile(int zoom)
    {
        return TILE_SIZE / Math.pow(2, zoom);
    }

    void configure(boolean diskCache, int memoryLimit, int diskLimitMb)
    {
        boolean wasOn;
        boolean first;
        int oldLimit;
        synchronized (this)
        {
            wasOn = this.diskCache;
            oldLimit = this.diskLimitMb;
            first = !configured;
            configured = true;
            this.diskCache = diskCache;
            this.memoryLimit = memoryLimit;
            this.diskLimitMb = diskLimitMb;
            trim();
        }
        if (!diskCache && (wasOn || first))
        {
            execute(this::deleteCachedTiles);
        }
        else if (diskCache && wasOn && diskLimitMb < oldLimit)
        {
            requestTrim();
        }
    }

    String version()
    {
        return version;
    }

    /**
     * The tiles of {@code from} become {@code to}'s, but for those the server says changed or went, so only they are
     * fetched again: the folder is renamed, the changed tiles deleted, the tiles known missing kept but for the changed
     * ones. Before {@link #setVersion}. False when {@code from} is not on disk or {@code to} already is.
     */
    boolean upgrade(String from, String to, Set<String> changed, Set<String> removed)
    {
        Filepath old = versionFolder(from);
        Filepath fresh = versionFolder(to);
        if (!diskCache || !old.isDirectory() || fresh.exists())
        {
            return false;
        }
        Set<String> gone = new HashSet<>(changed);
        gone.addAll(removed);
        synchronized (missingFile)
        {
            try
            {
                for (String path : gone)
                {
                    String[] p = path.split("/");
                    Filepath tile = p.length == 3 ? old.join(p[0], p[1], p[2]) : null;
                    if (tile != null && tile.isFile())
                    {
                        diskBytes.addAndGet(-tile.size());
                        tile.delete();
                    }
                }
                Filepath missingList = old.joinSegment(MISSING);
                if (missingList.isFile())
                {
                    StringBuilder kept = new StringBuilder();
                    try (BufferedReader in = missingList.openBufferedReader())
                    {
                        String line;
                        while ((line = in.readLine()) != null)
                        {
                            String[] p = line.trim().split(" ");
                            String path = p.length == 5 ? p[0] + "/" + p[1] + "/" + p[2] + "_" + p[3] + "_" + p[4] + ".png" : "";
                            if (!path.isEmpty() && !changed.contains(path))
                            {
                                kept.append(line.trim()).append('\n');
                            }
                        }
                    }
                    missingList.write(kept.toString());
                }
                old.moveTo(fresh);
                return true;
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Could not carry the tiles of {} over to {}", from, to, e);
                return false;
            }
        }
    }

    /** {@code official}: the version the map server reports, not a manual setting. */
    void setVersion(String version, boolean official)
    {
        if (version.equals(this.version))
        {
            return;
        }
        // Listed outside the lock: the map keeps painting meanwhile.
        String fallback = previousOnDisk(version);
        synchronized (this)
        {
            if (version.equals(this.version))
            {
                return;
            }
            this.version = version;
            clear();
            cleanupPending = official;
            this.official = official;
            fallbackVersion = fallback;
        }
        if (official && fallback != null)
        {
            // Older versions merge into the fallback, so their tiles still show while this version's arrive.
            execute(() -> mergeOlder(version, fallback));
        }
        execute(() -> loadMissing(version));
        requestTrim();
    }

    /** Per version, the tiles the map server has not got (open sea, the world's edge), so they aren't asked again. */
    private static final String MISSING = "missing.txt";
    private final Object missingFile = new Object();

    private void rememberMissing(String tileVersion, Key key)
    {
        if (!diskCache)
        {
            return;
        }
        synchronized (missingFile)
        {
            try
            {
                Filepath folder = versionFolder(tileVersion);
                folder.createDirectories();
                folder.joinSegment(MISSING).write(key.map + " " + key.zoom + " " + key.plane + " " + key.x + " " + key.y
                    + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            catch (IOException | RuntimeException e)
            {
                log.debug("Could not remember missing tile {}", key.path(), e);
            }
        }
    }

    /** Background thread. */
    private void loadMissing(String tileVersion)
    {
        Filepath file = versionFolder(tileVersion).joinSegment(MISSING);
        if (!diskCache || !file.isFile())
        {
            return;
        }
        Set<Key> keys = new HashSet<>();
        try (BufferedReader in = file.openBufferedReader())
        {
            String line;
            while ((line = in.readLine()) != null)
            {
                String[] p = line.trim().split(" ");
                try
                {
                    if (p.length == 5)
                    {
                        keys.add(new Key(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), Integer.parseInt(p[4])));
                    }
                }
                catch (NumberFormatException e)
                {
                    // A broken line: that tile is asked again.
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not read {}", file, e);
            return;
        }
        synchronized (this)
        {
            if (tileVersion.equals(version))
            {
                missing.addAll(keys);
            }
        }
    }

    private String previousOnDisk(String current)
    {
        Filepath best = null;
        for (Filepath dir : versionDirs())
        {
            if (!dir.getFileName().equals(current) && MapServer.isSafeVersion(dir.getFileName())
                && (best == null || modified(dir) > modified(best)))
            {
                best = dir;
            }
        }
        return best == null ? null : best.getFileName();
    }

    /** The tiles in view and half as many to spare, so panning back or zooming out reloads little. */
    static int need(int visibleTiles)
    {
        return visibleTiles + visibleTiles / 2 + 16;
    }

    /** Whether {@code view} may show {@code visibleTiles} with the cache under its cap, the other views' tiles counted. */
    synchronized boolean fits(Object view, int visibleTiles)
    {
        return need(visibleTiles) + frameNeed - needs.getOrDefault(view, 0) <= Math.max(memoryLimit, MAX_TILES);
    }

    synchronized void beginFrame(Object view, int visibleTiles)
    {
        queue.clear();
        queued.clear();
        setNeed(view, need(visibleTiles));
    }

    /**
     * {@code view} closed: with no map open only a few tiles stay, for the next opening. Another still open trims at its
     * next tile, once its own are the latest used.
     */
    synchronized void closed(Object view)
    {
        setNeed(view, 0);
        if (needs.isEmpty())
        {
            queue.clear();
            queued.clear();
            trim();
        }
    }

    private void setNeed(Object view, int need)
    {
        Integer old = need > 0 ? needs.put(view, need) : needs.remove(view);
        frameNeed += need - (old == null ? 0 : old);
    }

    synchronized void endFrame()
    {
        pump();
    }

    synchronized BufferedImage peek(Key key)
    {
        return memory.get(key);
    }

    /** Queue tiles farthest from the middle first. */
    synchronized BufferedImage get(Key key)
    {
        BufferedImage image = memory.get(key);
        Long failedAt = failed.get(key);
        if (!(image != null && !stale.contains(key) || version == null || missing.contains(key) || loading.contains(key)
            || queued.contains(key) || failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS))
        {
            queue.addFirst(key);
            queued.add(key);
        }
        return image;
    }

    /** Decoded ({@link #decode}), and on disk when that is on. */
    private BufferedImage keep(Filepath file, byte[] bytes, Color under) throws IOException
    {
        BufferedImage image = decode(bytes, under);
        if (image != null && diskCache)
        {
            write(file, bytes);
        }
        return image;
    }

    private Call call(String tileVersion, Key key)
    {
        return http.newCall(new Request.Builder().url(MapServer.tile(tileVersion, key)).build());
    }

    /** Null on 404. */
    private static byte[] bytes(Response response, ResponseBody body) throws IOException
    {
        if (response.code() == 404)
        {
            return null;
        }
        if (!response.isSuccessful() || body == null)
        {
            throw new IOException("HTTP " + response.code());
        }
        return readBody(body, MAX_TILE_BYTES);
    }

    synchronized void clear()
    {
        generation++;
        memory.clear();
        stale.clear();
        queue.clear();
        queued.clear();
        loading.clear();
        missing.clear();
        failed.clear();
        calls.forEach(Call::cancel);
        calls.clear();
    }

    private void pump()
    {
        while (inFlight < MAX_IN_FLIGHT && !queue.isEmpty())
        {
            Key key = queue.pollFirst();
            queued.remove(key);
            loading.add(key);
            inFlight++;
            int started = generation;
            String tileVersion = version;
            if (!execute(() -> load(key, tileVersion, started)))
            {
                loading.remove(key);
                inFlight--;
                return;
            }
        }
    }

    private boolean execute(Runnable task)
    {
        return execute(io, task);
    }

    /** Runs {@code task} on {@code io}; false once it refuses more work (the plugin stopping), for every such hand-off. */
    static boolean execute(Executor io, Runnable task)
    {
        try
        {
            io.execute(task);
            return true;
        }
        catch (RejectedExecutionException e)
        {
            return false;
        }
    }

    private Filepath file(String tileVersion, Key key)
    {
        return versionFolder(tileVersion).join(key.path());
    }

    private static final String PART_SUFFIX = ".part";

    /** Refused over {@code max} bytes whether or not the server declared the length. */
    static byte[] readBody(ResponseBody body, int max) throws IOException
    {
        long declared = body.contentLength();
        if (declared > max)
        {
            throw new IOException("Too large: " + declared + " bytes");
        }
        try (InputStream in = body.byteStream())
        {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max)
            {
                throw new IOException("Too large: over " + max + " bytes");
            }
            return bytes;
        }
    }

    Filepath versionFolder(String tileVersion)
    {
        return cacheRoot.joinSegment(tileVersion);
    }

    /** For a map's first frame, which would otherwise show icons on black before tiles pop in; not tiles known missing. */
    BufferedImage fromDiskNow(Key key)
    {
        String current;
        synchronized (this)
        {
            current = missing.contains(key) || failed.containsKey(key) ? null : version;
        }
        BufferedImage image = current == null ? null : readCached(file(current, key), under(key));
        if (image != null)
        {
            synchronized (this)
            {
                if (current.equals(version))
                {
                    memory.put(key, image);
                    trim();
                }
            }
        }
        return image;
    }

    /** Files that are no tile are removed so they download again; a failed read leaves the file. */
    private BufferedImage readCached(Filepath file, Color under)
    {
        if (!diskCache || !file.isFile())
        {
            return null;
        }
        byte[] bytes;
        try
        {
            bytes = file.size() > MAX_TILE_BYTES ? null : read(file);
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not read cached tile {}", file, e);
            return null;
        }
        try
        {
            BufferedImage image = bytes == null ? null : decode(bytes, under);
            if (image != null)
            {
                return image;
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Unreadable cached tile {}", file, e);
        }
        delete(file);
        return null;
    }

    private void load(Key key, String tileVersion, int started)
    {
        try
        {
            BufferedImage cached = readCached(file(tileVersion, key), under(key));
            if (cached != null)
            {
                finish(key, started, cached, false, false);
                return;
            }
            String previous = fallbackVersion;
            Filepath old = previous == null ? null : file(previous, key);
            BufferedImage stale = old == null ? null : readCached(old, under(key));
            if (stale != null)
            {
                showStale(key, started, stale);
            }
            Call download = call(tileVersion, key);
            calls.add(download);
            download.enqueue(new Callback()
            {
                @Override
                public void onFailure(Call call, IOException e)
                {
                    calls.remove(call);
                    log.debug("Tile download failed: {}", key.path(), e);
                    finish(key, started, null, false, false);
                }

                @Override
                public void onResponse(Call call, Response response)
                {
                    calls.remove(call);
                    byte[] bytes;
                    try (ResponseBody body = response.body())
                    {
                        bytes = bytes(response, body);
                    }
                    catch (IOException | RuntimeException e)
                    {
                        log.debug("Unusable tile {}", key.path(), e);
                        finish(key, started, null, false, false);
                        return;
                    }
                    // The disk work on our own thread, not OkHttp's shared callback threads.
                    if (!execute(() -> store(key, tileVersion, started, old, bytes)))
                    {
                        finish(key, started, null, false, false);
                    }
                }
            });
        }
        catch (RuntimeException e)
        {
            log.debug("Could not load tile {}", key.path(), e);
            finish(key, started, null, false, false);
        }
    }

    /** A downloaded tile kept on disk; {@code bytes} null: the map server has none in this version. */
    private void store(Key key, String tileVersion, int started, Filepath old, byte[] bytes)
    {
        BufferedImage image = null;
        try
        {
            if (bytes == null)
            {
                // Gone in this version: the stale copy must not linger.
                forget(key, started);
                rememberMissing(tileVersion, key);
            }
            else
            {
                // Not an image although found: a passing fault, asked again later.
                image = keep(file(tileVersion, key), bytes, under(key));
                if (image != null && old != null && official && old.isFile())
                {
                    diskBytes.addAndGet(-old.size());
                    delete(old);
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Unusable tile {}", key.path(), e);
        }
        finish(key, started, image, bytes == null, image != null);
    }

    /** The key stays loading. */
    private void showStale(Key key, int started, BufferedImage image)
    {
        synchronized (this)
        {
            if (started != generation || memory.containsKey(key))
            {
                return;
            }
            memory.put(key, image);
            stale.add(key);
            trim();
        }
        onLoaded.run();
    }

    private synchronized void forget(Key key, int started)
    {
        if (started == generation)
        {
            memory.remove(key);
            stale.remove(key);
        }
    }

    private void finish(Key key, int started, BufferedImage image, boolean notFound, boolean downloaded)
    {
        boolean cleanup = false;
        synchronized (this)
        {
            inFlight--;
            if (started != generation)
            {
                pump();
                return;
            }
            loading.remove(key);
            if (image != null)
            {
                memory.put(key, image);
                stale.remove(key);
                failed.remove(key);
                trim();
                if (downloaded && cleanupPending)
                {
                    cleanupPending = false;
                    cleanup = true;
                }
            }
            else if (notFound)
            {
                missing.add(key);
            }
            else
            {
                failed.put(key, System.currentTimeMillis());
            }
            pump();
        }
        if (cleanup)
        {
            String keep = version;
            String fallback = fallbackVersion;
            execute(() -> removeOtherVersions(keep, fallback));
        }
        if (image != null)
        {
            onLoaded.run();
        }
    }

    private void trim()
    {
        int limit = needs.isEmpty() ? Math.min(memoryLimit, CLOSED_TILES) : Math.max(memoryLimit, frameNeed);
        while (memory.size() > limit)
        {
            Key oldest = memory.keySet().iterator().next();
            memory.remove(oldest);
            stale.remove(oldest);
        }
    }

    /**
     * What a tile shown in a view is drawn on: the ground floor is drawn on the map's background, so filled with it the
     * tile stays opaque, which Java2D scales several times faster (zoomed out most tiles have see-through parts). Upper
     * floors stay see-through: the ground floor shows under them ({@link MapView}).
     */
    static Color under(Key key)
    {
        return key.plane == 0 ? MapView.BACKGROUND : null;
    }

    /** A picture, its size checked before its pixels are read; null when it is no picture. */
    static BufferedImage readImage(byte[] bytes, BiPredicate<Integer, Integer> sizeOk) throws IOException
    {
        // In memory: ImageIO's default stream would cache to a temporary file.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
        {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
            {
                return null;
            }
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!sizeOk.test(width, height))
                {
                    throw new IOException("Unexpected picture size: " + width + "x" + height);
                }
                return reader.read(0);
            }
            finally
            {
                reader.dispose();
            }
        }
    }

    /**
     * Into a type Java2D draws quickly; anything but 256x256 is refused before it is decoded. See-through parts are
     * filled with {@code under} when given ({@link #under}).
     */
    static BufferedImage decode(byte[] bytes, Color under) throws IOException
    {
        BufferedImage source = readImage(bytes, (width, height) -> width == TILE_SIZE && height == TILE_SIZE);
        if (source == null)
        {
            return null;
        }
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), alpha && under == null
            ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        if (alpha && under != null)
        {
            g.setColor(under);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        }
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return image;
    }

    private void write(Filepath file, byte[] bytes)
    {
        try
        {
            writeAtomically(file, bytes);
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not cache tile {}", file, e);
            return;
        }
        if (diskBytes.addAndGet(bytes.length) > diskLimitMb * MB)
        {
            requestTrim();
        }
    }

    /**
     * Through an own temp file (two threads may write the same file at once), then moved over the target; makes the
     * folder, which another thread may make at the same moment.
     */
    static void writeAtomically(Filepath target, byte[] bytes) throws IOException
    {
        Filepath folder = target.getParent();
        folder.createDirectories();
        Filepath temp = folder.createTempFile(target.getFileName() + ".", PART_SUFFIX);
        try
        {
            temp.write(bytes);
            try
            {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            temp.deleteIfExists();
        }
    }

    static byte[] read(Filepath file) throws IOException
    {
        try (InputStream in = file.openInputStream())
        {
            return in.readAllBytes();
        }
    }

    /** One trim at a time: the flag stays set until it ends, so writes meanwhile queue no second one. */
    private void requestTrim()
    {
        if (trimQueued.compareAndSet(false, true) && !execute(this::limitDiskUse))
        {
            trimQueued.set(false);
        }
    }

    /** Moves tiles of other versions that {@code fallback} lacks into it, then removes those versions. */
    private void mergeOlder(String current, String fallback)
    {
        Filepath into = versionFolder(fallback);
        for (Filepath dir : versionDirs())
        {
            String name = dir.getFileName();
            if (name.equals(current) || name.equals(fallback) || !MapServer.isSafeVersion(name))
            {
                continue;
            }
            for (Filepath file : files(dir))
            {
                try
                {
                    // Tiles lie in version/map/zoom.
                    Filepath zoom = file.getParent();
                    Filepath map = zoom.getParent();
                    Filepath target = into.join(map.getFileName(), zoom.getFileName(), file.getFileName());
                    if (dir.equals(map.getParent()) && !target.exists())
                    {
                        target.getParent().createDirectories();
                        file.moveTo(target);
                    }
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("Could not keep {}", file, e);
                }
            }
            deleteTree(dir);
        }
    }

    private void removeOtherVersions(String keep, String fallback)
    {
        for (Filepath dir : versionDirs())
        {
            if (!dir.getFileName().equals(keep) && !dir.getFileName().equals(fallback))
            {
                deleteTree(dir);
            }
        }
    }

    /** Snapshot: the disk may change while trimming. */
    static final class Entry
    {
        final Filepath file;
        final long modified;
        final long size;

        Entry(Filepath file) throws IOException
        {
            this.file = file;
            modified = file.getLastModifiedTime().toMillis();
            size = file.size();
        }
    }

    /** Background thread: removes oldest tiles past the limit and stale half-written files. */
    void limitDiskUse()
    {
        try
        {
            trimDisk();
        }
        finally
        {
            trimQueued.set(false);
        }
    }

    private void trimDisk()
    {
        if (!diskCache)
        {
            return;
        }
        long counted = diskBytes.get();
        long now = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        for (Filepath file : files(cacheRoot))
        {
            String name = file.getFileName();
            try
            {
                Entry entry = new Entry(file);
                if (name.endsWith(PART_SUFFIX) && now - entry.modified > PART_STALE_MS)
                {
                    delete(file);
                    continue;
                }
                entries.add(entry);
                total += entry.size;
            }
            catch (IOException e)
            {
                // Removed meanwhile.
            }
        }
        total -= trimOldest(entries, total, diskLimitMb * MB);
        // Tiles written during the walk still count.
        diskBytes.addAndGet(total - counted);
    }

    /** Removes the oldest files until 80% of {@code limit} is left, if over it; returns the bytes removed. */
    static long trimOldest(List<Entry> entries, long total, long limit)
    {
        long removed = 0;
        if (total > limit)
        {
            entries.sort(Comparator.comparingLong((Entry e) -> e.modified));
            for (Entry entry : entries)
            {
                if (total - removed <= limit * 8 / 10)
                {
                    break;
                }
                if (delete(entry.file))
                {
                    removed += entry.size;
                }
            }
        }
        return removed;
    }

    /** Background thread: the disk cache was switched off, so its tiles go. */
    private void deleteCachedTiles()
    {
        if (diskCache)
        {
            return;
        }
        for (Filepath dir : versionDirs())
        {
            if (MapServer.isSafeVersion(dir.getFileName()))
            {
                deleteTree(dir);
            }
        }
        diskBytes.set(0);
    }

    private List<Filepath> versionDirs()
    {
        return walk(cacheRoot, 1, dir -> !dir.equals(cacheRoot) && dir.isDirectory());
    }

    /** Files below {@code dir}; symbolic links are not followed. */
    static List<Filepath> files(Filepath dir)
    {
        return walk(dir, Integer.MAX_VALUE, Filepath::isFile);
    }

    private static List<Filepath> walk(Filepath dir, int depth, Predicate<Filepath> filter)
    {
        try (Stream<Filepath> found = dir.walk(depth))
        {
            return found.filter(filter).collect(Collectors.toList());
        }
        catch (IOException | UncheckedIOException e)
        {
            return Collections.emptyList();
        }
    }

    static long modified(Filepath file)
    {
        try
        {
            return file.getLastModifiedTime().toMillis();
        }
        catch (IOException e)
        {
            return 0;
        }
    }

    /** Never follows symbolic links. */
    static void deleteTree(Filepath file)
    {
        try
        {
            file.deleteRecursively();
        }
        catch (IOException e)
        {
            log.debug("Could not remove {}", file, e);
        }
    }

    static boolean delete(Filepath file)
    {
        try
        {
            file.deleteIfExists();
            return true;
        }
        catch (IOException e)
        {
            log.debug("Could not remove {}", file, e);
            return false;
        }
    }
}
