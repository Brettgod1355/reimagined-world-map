package com.hdmapreforged;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.lang.ref.*;
import java.util.*;
import java.util.List;
import lombok.*;

/**
 * Tiles pre-scaled to their drawn size: smooth scaling every frame was the most expensive part of painting. Swing
 * thread only.
 */
final class ScaledTiles
{
    /** Larger tiles would take a lot of memory and scale cheaply anyway. */
    private static final int MAX_PIXELS = 512 * 512;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** Copies made per frame, in pixels (about 3 ms of smooth scaling): after a zoom, all at once took 16-30 ms. */
    static final int FRAME_PIXELS = 1 << 19;

    private static final class Entry
    {
        /** Weak, so a tile the cache dropped is not kept alive here. */
        final WeakReference<BufferedImage> source;
        final BufferedImage scaled;
        final boolean smooth;
        /** The frame that last drew it; never dropped for another copy that frame, or each would be made again. */
        int frame;

        Entry(BufferedImage source, BufferedImage scaled, boolean smooth)
        {
            this.source = new WeakReference<>(source);
            this.scaled = scaled;
            this.smooth = smooth;
        }

        long bytes()
        {
            return 4L * scaled.getWidth() * scaled.getHeight();
        }
    }

    @RequiredArgsConstructor
    private static final class Wanted
    {
        final TileCache.Key key;
        final BufferedImage source;
        final int width;
        final int height;
        final boolean smooth;
    }

    private final LinkedHashMap<TileCache.Key, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);
    private int limit = 64;
    private long bytes;
    private int frame;
    /** Tiles this frame drew the fast way for want of a copy, farthest from the middle first. */
    private final List<Wanted> wanted = new ArrayList<>();

    /** Before a frame's tiles: keeps at most {@code tiles} copies. */
    void beginFrame(int tiles)
    {
        limit = Math.max(16, tiles);
        frame++;
        wanted.clear();
        trim();
    }

    /**
     * Makes the copies this frame lacked, the middle first, a frame's share; true when more wait for the next frame.
     * With no room left (a view too large for {@link #MAX_BYTES}) the rest stay drawn the fast way.
     */
    boolean endFrame()
    {
        int pixels = FRAME_PIXELS;
        for (int i = wanted.size() - 1; i >= 0 && !full(4L * wanted.get(i).width * wanted.get(i).height); i--)
        {
            if (pixels <= 0)
            {
                wanted.clear();
                return true;
            }
            Wanted want = wanted.get(i);
            pixels -= want.width * want.height;
            Entry entry = new Entry(want.source, scale(want.source, want.width, want.height, want.smooth), want.smooth);
            entry.frame = frame;
            Entry old = cache.put(want.key, entry);
            bytes += entry.bytes() - (old == null ? 0 : old.bytes());
            trim();
        }
        wanted.clear();
        return false;
    }

    /** No room for {@code more} bytes without dropping a copy this frame drew. */
    private boolean full(long more)
    {
        return (cache.size() >= limit || bytes + more > MAX_BYTES) && !cache.isEmpty()
            && cache.values().iterator().next().frame == frame;
    }

    void clear()
    {
        cache.clear();
        bytes = 0;
    }

    /** A new copy is only made when {@code settled} (the zoom is not animating), at {@link #endFrame}. */
    void draw(Graphics2D g, TileCache.Key key, BufferedImage source, int x, int y, int width, int height,
        boolean settled, boolean smooth)
    {
        AffineTransform transform = g.getTransform();
        double device = Math.abs(transform.getScaleX());
        int dw = (int) Math.round(width * device);
        int dh = (int) Math.round(height * device);
        if (dw <= 0 || dh <= 0)
        {
            return;
        }
        if (dw == source.getWidth() && dh == source.getHeight() || (long) dw * dh > MAX_PIXELS
            || transform.getShearX() != 0 || transform.getShearY() != 0)
        {
            g.drawImage(source, x, y, width, height, null);
            return;
        }
        Entry entry = cache.get(key);
        if (entry == null || entry.source.get() != source || entry.smooth != smooth || entry.scaled.getWidth() != dw
            || entry.scaled.getHeight() != dh)
        {
            if (settled)
            {
                wanted.add(new Wanted(key, source, dw, dh, smooth));
            }
            // Meanwhile scaled the fast way, as while moving.
            Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, x, y, width, height, null);
            if (hint != null)
            {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            }
            return;
        }
        entry.frame = frame;
        if (device == 1)
        {
            g.drawImage(entry.scaled, x, y, null);
        }
        else
        {
            AffineTransform place = new AffineTransform(transform);
            place.translate(x, y);
            place.scale(width / (double) dw, height / (double) dh);
            Graphics2D copy = (Graphics2D) g.create();
            try
            {
                copy.setTransform(place);
                copy.drawImage(entry.scaled, 0, 0, null);
            }
            finally
            {
                copy.dispose();
            }
        }
    }

    static BufferedImage scale(BufferedImage source, int width, int height, boolean smooth)
    {
        BufferedImage scaled = new BufferedImage(width, height, source.getColorModel().hasAlpha()
            ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
            : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private void trim()
    {
        Iterator<Entry> it = cache.values().iterator();
        while ((cache.size() > limit || bytes > MAX_BYTES) && it.hasNext())
        {
            Entry eldest = it.next();
            if (eldest.frame == frame)
            {
                return;
            }
            bytes -= eldest.bytes();
            it.remove();
        }
    }
}
