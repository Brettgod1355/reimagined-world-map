package com.hdmapreforged;

import static java.awt.RenderingHints.*;
import static java.lang.Math.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Vector map icons, so they stay sharp at every zoom level and interface scale. */
final class PoiIcons
{
    static final Color OUTLINE = new Color(20, 18, 16);
    static final Color GLYPH = new Color(255, 255, 255, 240);

    /** Rendered badges by type, size and screen scale. */
    static final Map<Long, BufferedImage> SPRITES = new ConcurrentHashMap<>();
    /** The pixels they hold together: zooming makes a size per quarter pixel, and 4000 large ones were tens of MB. */
    static final AtomicLong SPRITE_PIXELS = new AtomicLong();

    /** {@link #paint} from a cached image: painting hundreds of badges shape by shape was most of a frame's cost. */
    static void paintCached(Graphics2D g, PoiType type, double cx, double cy, double size, boolean emphasised,
        boolean locked)
    {
        var transform = g.getTransform();
        double device = device(transform);
        // Quarter pixels of badge size, so zooming changes the image in small steps.
        int quarter = (int) round(size * 4);
        long key = (long) type.ordinal() << 40 | (locked ? 1L << 39 : 0) | (long) quarter << 16 | round(device * 10) << 1
            | (emphasised ? 1 : 0);
        double drawnSize = quarter / 4.0;
        // Room around the badge for the shadow and the emphasis ring.
        double extent = drawnSize + (ceil(drawnSize * 0.35) + 2) * 2;
        BufferedImage sprite = SPRITES.get(key);
        if (sprite == null)
        {
            // At most 4000 sprites and 4 million pixels.
            if (SPRITES.size() > 4000 || SPRITE_PIXELS.get() > 4_000_000)
            {
                SPRITES.clear();
                SPRITE_PIXELS.set(0);
            }
            sprite = sprite(extent, extent, device, sg -> {
                sg.setRenderingHint(KEY_STROKE_CONTROL, VALUE_STROKE_PURE);
                paint(sg, type, extent / 2, extent / 2, drawnSize, emphasised, locked);
            });
            SPRITES.put(key, sprite);
            SPRITE_PIXELS.addAndGet((long) sprite.getWidth() * sprite.getHeight());
        }
        blit(g, transform, sprite, cx - extent / 2, cy - extent / 2);
    }

    /** Draws a screen-resolution sprite on whole screen pixels: at fractions Java2D resamples it each draw (slow). */
    static void blit(Graphics2D g, BufferedImage sprite, double x, double y)
    {
        blit(g, g.getTransform(), sprite, x, y);
    }

    /** {@code transform}: the graphics' own. */
    static void blit(Graphics2D g, AffineTransform transform, BufferedImage sprite, double x, double y)
    {
        double tx = transform.getTranslateX();
        double ty = transform.getTranslateY();
        if (transform.getType() <= AffineTransform.TYPE_TRANSLATION && tx == rint(tx) && ty == rint(ty))
        {
            // Screen scale 1 on whole pixels: drawn in place, without swapping the transform (costly).
            g.drawImage(sprite, (int) round(x), (int) round(y), null);
            return;
        }
        if ((transform.getType() & (AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_QUADRANT_ROTATION
            | AffineTransform.TYPE_GENERAL_TRANSFORM | AffineTransform.TYPE_FLIP)) != 0)
        {
            double device = device(transform);
            var place = new AffineTransform(transform);
            place.translate(x, y);
            place.scale(1 / device, 1 / device);
            g.drawImage(sprite, place, null);
            return;
        }
        long sx = round(transform.getScaleX() * x + tx);
        long sy = round(transform.getScaleY() * y + ty);
        g.setTransform(AffineTransform.getTranslateInstance(sx, sy));
        g.drawImage(sprite, 0, 0, null);
        g.setTransform(transform);
    }

    /** A white dot with a coloured rim, marking where a line ends; from a cached image. */
    static void paintDot(Graphics2D g, double cx, double cy, Color rim)
    {
        double device = device(g.getTransform());
        long key = (1L << 62) | ((long) (rim.getRGB() & 0xffffffffL) << 8) | (int) round(device * 10);
        double extent = 13;
        blit(g, SPRITES.computeIfAbsent(key, k -> sprite(extent, extent, device, sg -> {
            var dot = new Ellipse2D.Double(extent / 2 - 4.5, extent / 2 - 4.5, 9, 9);
            sg.setColor(Color.WHITE);
            sg.fill(dot);
            sg.setColor(rim);
            sg.setStroke(new BasicStroke(2f));
            sg.draw(dot);
        })), cx - extent / 2, cy - extent / 2);
    }

    /**
     * A game sprite with the game's sign for what one cannot use yet, in its own pixels as the game draws it: red on the
     * pixels just inside its outline, all round, and a band three pixels wide from top right to bottom left. Once per
     * sprite.
     */
    static BufferedImage locked(BufferedImage icon)
    {
        return LOCKED_SPRITES.computeIfAbsent(icon, k -> {
            int w = icon.getWidth();
            int h = icon.getHeight();
            var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int rgb = icon.getRGB(x, y);
                    boolean red = drawn(icon, x, y) && !edge(icon, x, y) && (abs(x + y - (w + h - 2) / 2.0) <= 1
                        || edge(icon, x + 1, y) || edge(icon, x - 1, y) || edge(icon, x, y + 1) || edge(icon, x, y - 1));
                    out.setRGB(x, y, red ? LOCKED.getRGB() : rgb);
                }
            }
            return out;
        });
    }

    /** Whether the pixel is on the sprite's outline: drawn, beside one that is not. */
    static boolean edge(BufferedImage icon, int x, int y)
    {
        return drawn(icon, x, y)
            && !(drawn(icon, x + 1, y) && drawn(icon, x - 1, y) && drawn(icon, x, y + 1) && drawn(icon, x, y - 1));
    }

    static boolean drawn(BufferedImage icon, int x, int y)
    {
        return x >= 0 && y >= 0 && x < icon.getWidth() && y < icon.getHeight() && icon.getRGB(x, y) >>> 24 != 0;
    }

    static final Map<BufferedImage, BufferedImage> LOCKED_SPRITES = Collections.synchronizedMap(new WeakHashMap<>());
    static final Color LOCKED = new Color(230, 20, 20);

    /** Screen pixels per unit, clamped to a sane range; shared with {@link TextSprites}. */
    static double device(AffineTransform transform)
    {
        return max(1, min(4, abs(transform.getScaleX())));
    }

    /** A picture of {@code width} by {@code height} units, {@code device} pixels a unit (rounded up), drawn antialiased. */
    static BufferedImage sprite(double width, double height, double device, Consumer<Graphics2D> draw)
    {
        BufferedImage sprite = new BufferedImage((int) ceil(width * device), (int) ceil(height * device),
            BufferedImage.TYPE_INT_ARGB);
        var sg = sprite.createGraphics();
        sg.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        sg.scale(device, device);
        draw.accept(sg);
        sg.dispose();
        return sprite;
    }

    static void clearCache()
    {
        SPRITES.clear();
    }

    /** The game's own map icon for the type if it has one, else a round badge in the type's color with a glyph. */
    static void paint(Graphics2D g, PoiType type, double cx, double cy, double size, boolean emphasised, boolean locked)
    {
        BufferedImage game = GameIconSprites.get(type);
        if (game != null)
        {
            paintGameIcon(g, locked ? locked(game) : game, cx, cy, size, emphasised);
            return;
        }
        double r = size / 2;
        var badge = PlayerMarker.circle(cx, cy, r);
        if (emphasised)
        {
            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new BasicStroke((float) (size * 0.16)));
            g.draw(new Ellipse2D.Double(cx - r - size * 0.14, cy - r - size * 0.14, size * 1.28, size * 1.28));
        }
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(cx - r + size * 0.06, cy - r + size * 0.1, size, size));
        g.setColor(type.color);
        g.fill(badge);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke((float) max(1, size * 0.08)));
        g.draw(badge);
        g.setColor(GLYPH);
        g.setStroke(new BasicStroke((float) max(1, size * 0.09), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        draw(g, GLYPHS.getOrDefault(type.name(), "O -0.4 -0.4 0.8 0.8"), cx, cy, r * 0.62);
    }

    static void paintGameIcon(Graphics2D g, BufferedImage icon, double cx, double cy, double size, boolean emphasised)
    {
        // Pixel art in whole pixels: enlarged by whole times (the nearest, at least once), made smaller smoothly.
        double fit = size / max(icon.getWidth(), icon.getHeight());
        double scale = fit >= 1 ? max(1, round(fit)) : fit;
        double w = icon.getWidth() * scale;
        double h = icon.getHeight() * scale;
        if (emphasised)
        {
            double r = max(w, h) / 2 + size * 0.18;
            g.setColor(new Color(255, 255, 255, 210));
            g.setStroke(new BasicStroke((float) max(1.5, size * 0.12)));
            g.draw(PlayerMarker.circle(cx, cy, r));
        }
        var copy = (Graphics2D) g.create();
        copy.setRenderingHint(KEY_INTERPOLATION, scale >= 1
            ? VALUE_INTERPOLATION_NEAREST_NEIGHBOR : VALUE_INTERPOLATION_BILINEAR);
        copy.drawImage(icon, new AffineTransform(scale, 0, 0, scale, round(cx - w / 2), round(cy - h / 2)),
            null);
        copy.dispose();
    }

    /** Draws a glyph from {@code poi_glyphs.txt} in units of {@code s} around {@code (x, y)}; the file lists the ops. */
    static void draw(Graphics2D g, String ops, double x0, double y0, double s0)
    {
        // The origin and unit; U moves them until the path is painted.
        double x = x0;
        double y = y0;
        double s = s0;
        Path2D pen = new Path2D.Double();
        for (String op : ops.split(";"))
        {
            var t = op.trim().split(" ");
            var a = new double[8];
            for (int i = 1; i < t.length; i++)
            {
                a[i] = Double.parseDouble(t[i]);
            }
            double px = x + s * a[1];
            double py = y + s * a[2];
            double w = s * a[3];
            double h = s * a[4];
            boolean fill = Character.isUpperCase(t[0].charAt(0));
            switch (t[0])
            {
                case "g":
                    pen = new GeneralPath();
                    break;
                case "m":
                    pen.moveTo(px, py);
                    break;
                case "l":
                    pen.lineTo(px, py);
                    break;
                case "q":
                    pen.quadTo(px, py, x + w, y + h);
                    break;
                case "b":
                    pen.curveTo(px, py, x + w, y + h, x + s * a[5], y + s * a[6]);
                    break;
                case "z":
                    pen.closePath();
                    break;
                case "P":
                case "p":
                    shape(g, pen, fill);
                    pen = new Path2D.Double();
                    x = x0;
                    y = y0;
                    s = s0;
                    break;
                case "U":
                    x = px;
                    y = py;
                    s = w;
                    break;
                case "R":
                    g.fill(new Rectangle2D.Double(px, py, w, h));
                    break;
                case "O":
                case "o":
                    shape(g, new Ellipse2D.Double(px, py, w, h), fill);
                    break;
                case "L":
                    g.draw(new Line2D.Double(px, py, x + w, y + h));
                    break;
                case "A":
                case "a":
                    shape(g, new Arc2D.Double(px, py, w, h, a[5], a[6], (int) a[7]), fill);
                    break;
                case "W":
                    g.fill(new RoundRectangle2D.Double(px, py, w, h, s * a[5], s * a[5]));
                    break;
                case "c":
                    g.fill(PlayerMarker.circle(px, py, w));
                    break;
                case "C":
                    g.setColor(OUTLINE);
                    break;
                case "K":
                    g.setColor(new Color((int) a[1], (int) a[2], (int) a[3]));
                    break;
                case "w":
                    g.setStroke(a[2] == 1 ? new BasicStroke((float) a[1], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        : new BasicStroke((float) a[1]));
                    break;
                default:
                    throw new IllegalArgumentException(op);
            }
        }
    }

    /** Upper-case ops fill, lower-case ones outline. */
    static void shape(Graphics2D g, Shape shape, boolean fill)
    {
        if (fill)
        {
            g.fill(shape);
        }
        else
        {
            g.draw(shape);
        }
    }

    static final Map<String, String> GLYPHS = new HashMap<>();

    static
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            PoiIcons.class.getResourceAsStream("poi_glyphs.txt"), StandardCharsets.UTF_8)))
        {
            // A blank line or one without its tab is skipped, not a class that fails to load.
            reader.lines().map(line -> line.split("\t", 2))
                .filter(cells -> cells.length == 2 && !cells[0].startsWith("#"))
                .forEach(cells -> GLYPHS.put(cells[0], cells[1]));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /** A small image of an icon, for buttons and lists. */
    static BufferedImage image(PoiType type, int size)
    {
        return sprite(size + 2, size + 2, 1, g -> {
            g.setRenderingHint(KEY_STROKE_CONTROL, VALUE_STROKE_PURE);
            paint(g, type, (size + 2) / 2.0 - 0.5, (size + 2) / 2.0 - 1, size - 1, false, false);
        });
    }

    static BufferedImage icon(int size, String name)
    {
        return sprite(size, size, 1, g -> draw(g, GLYPHS.get(name), 0, 0, 1));
    }

    /** The sidebar button: a folded map. */
    static BufferedImage navigationIcon()
    {
        return icon(16, "NAVIGATION");
    }
}
