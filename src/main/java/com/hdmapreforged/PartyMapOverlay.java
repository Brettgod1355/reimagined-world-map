package com.hdmapreforged;

import static com.hdmapreforged.PlayerMarker.circle;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.api.coords.*;
import com.hdmapreforged.PartyMapMembers.Marker;

/** Party members on the map: a disc (or avatar) with their initial; name and world when pointed at or clicked. */
@RequiredArgsConstructor
final class PartyMapOverlay implements MapView.Overlay
{
    /** Distinct from the player's yellow and the icon colours. */
    static final Color[] COLORS = {
        new Color(64, 200, 255),
        new Color(255, 105, 180),
        new Color(90, 230, 120),
        new Color(255, 140, 60),
        new Color(170, 130, 255),
        new Color(60, 230, 210),
        new Color(255, 90, 90),
        new Color(200, 230, 80),
    };
    static final Color LABEL_BACKGROUND = new Color(20, 20, 24, 200);
    static final Font NAME_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    static final Font SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

    final Supplier<List<Marker>> markers;
    final IntSupplier myWorld;
    /** Only the clicked member and the one under the mouse show a name. */
    LongPredicate selected = id -> false;
    LongPredicate hovered = id -> false;

    LongFunction<List<PartyMapMembers.Loot>> loot = id -> Collections.emptyList();
    BiFunction<Integer, Integer, BufferedImage> itemImage = (id, q) -> null;
    Ticker ticker = new Ticker(() -> { });

    void setLoot(LongFunction<List<PartyMapMembers.Loot>> loot,
        BiFunction<Integer, Integer, BufferedImage> itemImage, Runnable repaint)
    {
        this.loot = loot;
        this.itemImage = itemImage;
        ticker.stop();
        ticker = new Ticker(repaint);
    }

    /**
     * Repaints ~30 times a second while something moves, instead of asking for a frame from inside paint (which never
     * lets the map rest); stops by itself once nothing moved for a moment. Swing thread only.
     */
    static final class Ticker
    {
        static final int FRAME_MS = 33;
        static final long LINGER_MS = 200;
        final Timer timer;
        long lastMoving;

        Ticker(Runnable repaint)
        {
            timer = new Timer(FRAME_MS, e -> {
                if (System.currentTimeMillis() - lastMoving > LINGER_MS)
                {
                    ((Timer) e.getSource()).stop();
                    return;
                }
                repaint.run();
            });
        }

        void painted(boolean moving)
        {
            if (moving)
            {
                lastMoving = System.currentTimeMillis();
                timer.start(); // no-op while running
            }
        }

        void stop()
        {
            timer.stop();
        }
    }

    static Color color(long id)
    {
        long mixed = id * 0x9E3779B97F4A7C15L;
        return COLORS[(int) ((mixed >>> 33) % COLORS.length)];
    }

    /** Lower case, with spaces, underscores and hyphens the same, as the game treats names. */
    static String nameKey(String name)
    {
        return name == null ? "" : name.replace(' ', ' ').replace('_', ' ').replace('-', ' ').trim()
            .toLowerCase(Locale.ROOT);
    }

    @Override
    public void paint(Graphics2D g, MapView.Projection projection)
    {
        var all = markers.get();
        if (all.isEmpty())
        {
            ticker.painted(false);
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        boolean rising = false;
        for (var marker : all)
        {
            if (!projection.shows(marker.point))
            {
                continue;
            }
            var at = projection.shown(marker.point);
            double x = projection.screenX(at.getX() + 0.5);
            double y = projection.screenY(at.getY() + 0.5);
            paintMarker(g, projection, marker, at, x, y, myWorld.getAsInt(), selected.test(marker.id),
                hovered.test(marker.id));
            rising |= paintLoot(g, projection, marker, x, y);
        }
        ticker.painted(rising);
    }

    static final Color UNTRADEABLE = new Color(240, 210, 120);

    /** Ground Items' colours for a value: medium, high, insane. */
    static Color lootColor(long value)
    {
        return value >= 10_000_000 ? new Color(255, 102, 178) : value >= 1_000_000 ? new Color(153, 255, 153)
            : new Color(102, 178, 255);
    }

    /** "1.2M", "450K", as Ground Items writes values. */
    static String shortValue(long value)
    {
        if (value >= 10_000_000)
        {
            return value / 1_000_000 + "M";
        }
        if (value >= 1_000_000)
        {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        return value / 1000 + "K";
    }

    /** Drops rising above the marker, newest lowest; true while any shows. */
    boolean paintLoot(Graphics2D g, MapView.Projection projection, Marker marker, double x, double y)
    {
        var showing = loot.apply(marker.id);
        if (showing.isEmpty())
        {
            return false;
        }
        // Off screen: nothing to keep the map drawing for.
        if (x < -120 || x > projection.width() + 120 || y < -40 || y > projection.height() + 40 + 22 * showing.size() + 60)
        {
            return false;
        }
        long now = System.currentTimeMillis();
        var layer = (Graphics2D) g.create();
        try
        {
            layer.setFont(NAME_FONT);
            var metrics = layer.getFontMetrics();
            for (int k = 0; k < showing.size(); k++)
            {
                var drop = showing.get(k);
                double t = Math.min(1, (now - drop.at) / (double) PartyMapMembers.LOOT_MS);
                double rise = 1 - Math.pow(1 - t, 3);
                float alpha = (float) (t < 0.75 ? 1 : 1 - (t - 0.75) / 0.25);
                layer.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, alpha)));
                double top = y - 22 - (showing.size() - 1 - k) * 22 - rise * 50;
                String text = drop.name != null ? drop.name : shortValue(drop.value);
                BufferedImage icon = itemImage.apply(drop.item, drop.quantity);
                int iconW = icon == null ? 0 : 26;
                double width = iconW + metrics.stringWidth(text) + 10;
                double left = x - width / 2;
                layer.setColor(new Color(20, 20, 24, 190));
                layer.fill(new RoundRectangle2D.Double(left, top - 12, width, 22, 10, 10));
                if (icon != null)
                {
                    layer.drawImage(icon, (int) left + 2, (int) top - 12, 26, 22, null);
                }
                layer.setColor(Color.BLACK);
                layer.drawString(text, (float) (left + iconW + 6), (float) top + 4);
                layer.setColor(drop.name != null ? UNTRADEABLE : lootColor(drop.value));
                layer.drawString(text, (float) (left + iconW + 5), (float) top + 3);
            }
        }
        finally
        {
            layer.dispose();
        }
        return true;
    }

    static void paintMarker(Graphics2D g, MapView.Projection projection, Marker marker, WorldPoint at, double x,
        double y, int myWorld, boolean chosen, boolean hover)
    {
        if (x < -40 || y < -40 || x > projection.width() + 40 || y > projection.height() + 40)
        {
            return;
        }
        float alpha = marker.alpha;
        boolean otherFloor = at.getPlane() != projection.plane();
        if (otherFloor)
        {
            alpha *= 0.6f;
        }
        var layer = (Graphics2D) g.create();
        try
        {
            layer.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            Color color = color(marker.id);
            double r = 7.5;
            var disc = circle(x, y, r);
            if (marker.house)
            {
                PlayerMarker.box(layer, x, y, r, color);
                layer.setFont(NAME_FONT);
                layer.drawString("House", (float) (x - layer.getFontMetrics().stringWidth("House") / 2.0),
                    (float) (y - r - 12));
            }
            layer.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
            layer.fill(circle(x, y, r + 5));
            if (marker.avatar != null)
            {
                Shape clip = layer.getClip();
                layer.clip(disc);
                layer.drawImage(marker.avatar, (int) Math.round(x - r), (int) Math.round(y - r),
                    (int) Math.round(r * 2), (int) Math.round(r * 2), null);
                layer.setClip(clip);
            }
            else
            {
                layer.setColor(color);
                layer.fill(disc);
                String initial = marker.name == null || marker.name.isEmpty() ? "?"
                    : marker.name.substring(0, 1).toUpperCase(Locale.ROOT);
                layer.setFont(NAME_FONT);
                var metrics = layer.getFontMetrics();
                layer.setColor(new Color(15, 15, 20));
                layer.drawString(initial, (float) (x - metrics.stringWidth(initial) / 2.0),
                    (float) (y + metrics.getAscent() / 2.0 - 1.5));
            }
            // White: gold would read as the local player's yellow marker.
            ring(layer, disc, 2f, Color.WHITE);
            ring(layer, circle(x, y, r + 2), 1f, color);
            if (chosen)
            {
                ring(layer, circle(x, y, r + 5), 3f, Color.WHITE);
                ring(layer, circle(x, y, r + 8), 2f, color);
            }
            if (chosen || hover)
            {
                paintLabel(layer, marker, x, y + r + 4, color, otherFloor, otherWorld(marker, myWorld));
            }
        }
        finally
        {
            layer.dispose();
        }
    }

    static void ring(Graphics2D g, Shape shape, float width, Color color)
    {
        g.setStroke(new BasicStroke(width));
        g.setColor(color);
        g.draw(shape);
    }

    static void paintLabel(Graphics2D g, Marker marker, double x, double top, Color color,
        boolean otherFloor, boolean otherWorld)
    {
        String name = marker.name != null ? marker.name : "Party member";
        String detail = detail(marker, otherFloor, otherWorld);
        g.setFont(NAME_FONT);
        var nameMetrics = g.getFontMetrics();
        int nameWidth = nameMetrics.stringWidth(name);
        var smallMetrics = g.getFontMetrics(SMALL_FONT);
        int detailWidth = detail == null ? 0 : smallMetrics.stringWidth(detail);
        int width = Math.max(nameWidth, detailWidth) + 10;
        int height = nameMetrics.getHeight() + (detail == null ? 0 : smallMetrics.getHeight()) + 2;
        double left = x - width / 2.0;
        var box = new RoundRectangle2D.Double(left, top, width, height, 8, 8);
        g.setColor(LABEL_BACKGROUND);
        g.fill(box);
        ring(g, box, 1f, color);
        double textLeft = x - nameWidth / 2.0;
        float baseline = (float) (top + 1 + nameMetrics.getAscent());
        g.setColor(Color.WHITE);
        g.drawString(name, (float) textLeft, baseline);
        if (detail != null)
        {
            g.setFont(SMALL_FONT);
            g.setColor(otherWorld ? FriendsWidget.OTHER_WORLD : new Color(200, 200, 205));
            g.drawString(detail, (float) (x - detailWidth / 2.0),
                (float) (top + 1 + nameMetrics.getHeight() + smallMetrics.getAscent()));
        }
    }

    static boolean otherWorld(Marker marker, int myWorld)
    {
        return marker.world > 0 && myWorld > 0 && marker.world != myWorld;
    }

    /** "World 330 · floor 1 · 3 min ago", leaving out what is not worth saying. */
    static String detail(Marker marker, boolean otherFloor, boolean otherWorld)
    {
        var text = new StringJoiner(" · ");
        if (otherWorld)
        {
            text.add("World " + marker.world);
        }
        if (otherFloor)
        {
            text.add("floor " + marker.point.getPlane());
        }
        if (marker.stale())
        {
            text.add(ago(marker.ageMillis));
        }
        return text.length() == 0 ? null : text.toString();
    }

    static String ago(long millis)
    {
        long minutes = millis / 60_000;
        return minutes < 1 ? (millis / 1000) + " s ago" : minutes + " min ago";
    }
}
