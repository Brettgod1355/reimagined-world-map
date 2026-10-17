package com.hdmapreforged;

import static com.hdmapreforged.MapView.*;

import java.text.*;
import net.runelite.api.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import lombok.*;
import net.runelite.api.coords.*;

/** The friends button at the map's bottom left, opening into the party list and a member's details. Swing thread only. */
final class FriendsWidget implements Widget
{
    @RequiredArgsConstructor
    static final class Member
    {
        final long id;
        final String name;
    }

    @RequiredArgsConstructor
    static final class Row
    {
        final String name;
        final int world;
        final WorldPoint point;
        final Color color;
        /** The party member's id, or -1 for a friend from the game's list. */
        final long id;
    }

    interface Images
    {
        BufferedImage item(int id, int quantity);

        BufferedImage skill(int ordinal);

        default BufferedImage sprite(int id)
        {
            return null;
        }
    }

    static final Color OTHER_WORLD = new Color(255, 190, 120);
    static final Color FRIEND = new Color(150, 150, 160);
    static final Color WORLD = new Color(190, 190, 195);
    static final Font ROW = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    static final int WIDTH = 256;
    static final int LEFT = 12;
    static final int ROW_HEIGHT = 22;
    static final NumberFormat AMOUNT = NumberFormat.getIntegerInstance();

    final Supplier<List<PartyMapMembers.Marker>> party;
    final Supplier<List<Member>> group;
    final BooleanSupplier inGroup;
    final IntSupplier myWorld;
    final Supplier<Boolean> enabled;
    final Consumer<Row> focus;
    final Runnable repaint;
    final PartyMapOverlay.Ticker ticker;
    boolean dropsMoving;
    boolean open;
    long selected = -1;
    int page;
    boolean folded;
    LongFunction<PartyMapMembers.Gear> gear = id -> null;
    Images images;

    @Setter
    LongFunction<List<PartyMapMembers.Drop>> drops = id -> Collections.emptyList();

    long selected()
    {
        return open ? selected : -1;
    }

    BooleanSupplier canJoin = () -> false;
    Runnable join = () -> { };

    void show(long id)
    {
        open = true;
        selected = id;
    }

    FriendsWidget(Supplier<List<PartyMapMembers.Marker>> party, Supplier<List<Member>> group,
        BooleanSupplier inGroup, IntSupplier myWorld, Supplier<Boolean> enabled,
        Consumer<Row> focus, Runnable repaint)
    {
        this.party = party;
        this.group = group;
        this.inGroup = inGroup;
        this.myWorld = myWorld;
        this.enabled = enabled;
        this.focus = focus;
        this.repaint = repaint;
        this.ticker = new PartyMapOverlay.Ticker(repaint);
    }

    static List<Row> rows(List<PartyMapMembers.Marker> party, List<Member> group)
    {
        var rows = new ArrayList<Row>();
        var seen = new HashSet<String>();
        var ids = new HashSet<Long>();
        for (var marker : party)
        {
            String name = marker.name != null ? marker.name : "Party member";
            if (seen.add(PartyMapOverlay.nameKey(name)))
            {
                ids.add(marker.id);
                rows.add(new Row(name, marker.world, marker.point, PartyMapOverlay.color(marker.id), marker.id));
            }
        }
        for (Member member : group)
        {
            // Not logged in: nothing to show.
            if (member.name == null)
            {
                continue;
            }
            String name = member.name;
            if (!ids.contains(member.id) && seen.add(PartyMapOverlay.nameKey(name)))
            {
                rows.add(new Row(name, 0, null, null, member.id));
            }
        }
        return rows;
    }

    @Override
    public void paint(Graphics2D g, int width, int height, int bottom, Clicks clicks)
    {
        dropsMoving = false;
        try
        {
            paintWidget(g, width, bottom, clicks);
        }
        finally
        {
            ticker.painted(dropsMoving);
        }
    }

    List<Member> groupNow()
    {
        try
        {
            return group.get();
        }
        catch (ConcurrentModificationException e)
        {
            // Changed on another thread while read; the next frame has them.
            return Collections.emptyList();
        }
    }

    void paintWidget(Graphics2D g, int width, int bottom, Clicks clicks)
    {
        if (!enabled.get())
        {
            return;
        }
        var rows = rows(party.get(), groupNow());
        if (rows.isEmpty() && !inGroup.getAsBoolean())
        {
            if (canJoin.getAsBoolean())
            {
                clicks.add(tab(g, bottom, clicks, "Rejoin party"), join, "Join your last party again");
            }
            return;
        }
        int w = Math.min(WIDTH, width - 24);
        var tab = tab(g, bottom, clicks, "Party · " + rows.size() + (open ? "  ▾" : "  ▴"));
        double tabTop = tab.getY();
        clicks.add(tab, () -> {
            open = !open;
            repaint.run();
        }, open ? "Close the list" : "Your party: click someone to see where they are");
        if (!open)
        {
            return;
        }
        Row chosen = null;
        for (Row row : rows)
        {
            chosen = row.id == selected && selected >= 0 && images != null ? row : chosen;
        }
        selected = chosen != null ? selected : -1;
        if (rows.isEmpty())
        {
            String text = "No one else in your party yet";
            g.setFont(ROW);
            double boxWidth = Math.max(w, g.getFontMetrics().stringWidth(text) + 20);
            var alone = new RoundRectangle2D.Double(LEFT, tabTop - 4 - ROW_HEIGHT - 6, boxWidth,
                ROW_HEIGHT + 6, 10, 10);
            box(g, alone, CONTROL_FILL, CONTROL_EDGE);
            g.setColor(FRIEND);
            g.drawString(text, LEFT + 10, middle(alone.getCenterY(), g.getFontMetrics()));
            return;
        }
        // Opens upwards, as many rows as fit (with room for a chosen one's folded details above, so one can switch
        // between them); the last line then says how many more.
        int fit = Math.max(1, (int) ((tabTop - 60 - (chosen != null ? 34 + STATUS_H : 0)) / ROW_HEIGHT));
        var shown = rows.size() > fit ? rows.subList(0, Math.max(1, fit - 1)) : rows;
        int more = rows.size() - shown.size();
        int lines = shown.size() + (more > 0 ? 1 : 0);
        double listTop = tabTop - 4 - lines * ROW_HEIGHT - 6;
        var list = new RoundRectangle2D.Double(LEFT, listTop, w, lines * ROW_HEIGHT + 6, 10, 10);
        box(g, list, CONTROL_FILL, CONTROL_EDGE);
        g.setFont(ROW);
        var metrics = g.getFontMetrics();
        int mine = myWorld.getAsInt();
        for (int i = 0; i < shown.size(); i++)
        {
            Row row = shown.get(i);
            double top = listTop + 3 + i * ROW_HEIGHT;
            var area = new RoundRectangle2D.Double(LEFT + 3, top, w - 6, ROW_HEIGHT, 8, 8);
            if (row.point != null || row.id >= 0)
            {
                if (clicks.hovered(area) || row == chosen)
                {
                    g.setColor(CONTROL_HOVER);
                    g.fill(area);
                }
                clicks.add(area, () -> {
                    if (row.point != null)
                    {
                        focus.accept(row);
                    }
                    if (row.id >= 0)
                    {
                        selected = row.id;
                        repaint.run();
                    }
                }, "Look at " + row.name + (row.id >= 0 ? " and what they carry" : ""));
            }
            double cy = top + ROW_HEIGHT / 2.0;
            dot(g, row.color, LEFT + 10, cy - 4);
            String world = row.world > 0 ? "W" + row.world : "";
            int worldWidth = metrics.stringWidth(world);
            g.setColor(row.world > 0 && mine > 0 && row.world != mine ? OTHER_WORLD : WORLD);
            g.drawString(world, LEFT + w - 10 - worldWidth, middle(cy, metrics));
            g.setColor(row.point != null ? Color.WHITE : FRIEND);
            String name = fit(row.name, metrics, w - 38 - worldWidth);
            g.drawString(name, LEFT + 24, middle(cy, metrics));
        }
        if (more > 0)
        {
            double cy = listTop + 3 + shown.size() * ROW_HEIGHT + ROW_HEIGHT / 2.0;
            g.setColor(FRIEND);
            g.drawString("+" + more + " more", LEFT + 24, middle(cy, metrics));
        }
        if (chosen != null)
        {
            paintDetails(g, chosen, LEFT, w, listTop - 4, clicks);
        }
    }

    /** A member's pages, here and in the sidebar. */
    static final String[] PAGES = {"Items", "Worn", "Skills"};
    static final Color BOOSTED = new Color(120, 220, 120);
    static final Color DRAINED = new Color(230, 120, 110);
    static final int SLOT_W = 48;
    static final int SLOT_H = 40;
    static final int SKILL_W = 74;
    static final int SKILL_H = 30;
    /** Every page as tall as the inventory, so switching pages moves nothing. */
    static final int BODY_H = 7 * SLOT_H;
    static final int STATUS_H = Orbs.HEIGHT + 6;
    /** Column and row of each equipment slot, as in the game's worn items tab. */
    static final int[][] WORN = {{1, 0}, {0, 1}, {1, 1}, {0, 2}, {1, 2}, {2, 2}, null, {1, 3}, null, {0, 4},
        {1, 4}, null, {2, 4}, {2, 1}};

    void paintDetails(Graphics2D g, Row row, double x0, int w, double bottomY, Clicks clicks)
    {
        var shared = gear.apply(row.id);
        int panelW = Math.max(w, 4 * Orbs.WIDTH + 3 * 4 + 16);
        int full = 30 + 26 + STATUS_H + BODY_H + 10;
        // Folded (or the map too low for all of it): the name and the orbs only.
        boolean cramped = bottomY - full < 44;
        boolean compact = folded || cramped;
        int height = compact ? 30 + STATUS_H + 4 : full;
        double top = bottomY - height;
        var panel = new RoundRectangle2D.Double(x0, top, panelW, height, 12, 12);
        box(g, panel, CONTROL_FILL, CONTROL_EDGE);
        g.setFont(CONTROL_FONT);
        var title = g.getFontMetrics();
        if (row.point != null)
        {
            // The name looks at them again, as the panel stays open after one looks elsewhere.
            var name = new RoundRectangle2D.Double(x0 + 4, top + 4, panelW - (cramped ? 32 : 54), 22, 8, 8);
            if (clicks.hovered(name))
            {
                g.setColor(CONTROL_HOVER);
                g.fill(name);
            }
            clicks.add(name, () -> focus.accept(row), "Follow " + row.name);
        }
        dot(g, row.color, x0 + 12, top + 11);
        g.setColor(Color.WHITE);
        g.drawString(fit(row.name, title, panelW - 130), (float) (x0 + 26), (float) (top + 19));
        if (row.world > 0)
        {
            String world = "W" + row.world;
            g.setFont(ROW);
            var rowMetrics = g.getFontMetrics();
            int mine = myWorld.getAsInt();
            boolean elsewhere = mine > 0 && row.world != mine;
            double right = x0 + panelW - (cramped ? 34 : 56);
            g.setColor(elsewhere ? OTHER_WORLD : WORLD);
            g.drawString(world, (float) (right - rowMetrics.stringWidth(world)), (float) (top + 20));
        }
        titleButton(g, clicks, title, x0 + panelW - 26, top, "×", () -> {
            selected = -1;
            repaint.run();
        }, "Close");
        if (!cramped)
        {
            titleButton(g, clicks, title, x0 + panelW - 48, top, folded ? "+" : "\u2013", () -> {
                folded = !folded;
                repaint.run();
            }, folded ? "Unfold" : "Fold up: the name and the orbs only");
        }
        if (compact)
        {
            paintStatus(g, shared, x0 + 8, top + 28, panelW - 16);
            paintDrops(g, row.id, x0, top + height - 6, panelW);
            return;
        }
        double tabW = (panelW - 16) / 3.0;
        g.setFont(ROW);
        var metrics = g.getFontMetrics();
        for (int k = 0; k < PAGES.length; k++)
        {
            var tabShape = new RoundRectangle2D.Double(x0 + 8 + k * tabW, top + 30, tabW - 4, 20, 8, 8);
            g.setColor(k == page ? new Color(70, 76, 88, 235) : clicks.hovered(tabShape) ? CONTROL_HOVER : new Color(40, 43, 50, 220));
            g.fill(tabShape);
            g.setColor(k == page ? Color.WHITE : new Color(180, 180, 186));
            g.drawString(PAGES[k], (float) (tabShape.getCenterX() - metrics.stringWidth(PAGES[k]) / 2.0),
                (float) (top + 44));
            int chosen = k;
            clicks.add(tabShape, () -> {
                page = chosen;
                repaint.run();
            }, PAGES[k]);
        }
        paintStatus(g, shared, x0 + 8, top + 56, panelW - 16);
        double y0 = top + 56 + STATUS_H;
        if (shared == null)
        {
            g.setColor(FRIEND);
            g.drawString("Nothing shared yet.", (float) (x0 + 12), (float) (y0 + 16));
            g.drawString("They share it with this plugin's setting.", (float) (x0 + 12), (float) (y0 + 32));
            return;
        }
        double gridLeft = x0 + (panelW - (page == 2 ? 3 * SKILL_W : (page == 0 ? 4 : 3) * SLOT_W)) / 2.0;
        if (page == 0)
        {
            for (int k = 0; k < HdMapPartyGear.INVENTORY; k++)
            {
                slot(g, gridLeft + (k % 4) * SLOT_W, y0 + (k / 4) * SLOT_H, shared.inventory[k], shared.quantities[k]);
            }
        }
        else if (page == 1)
        {
            for (int k = 0; k < WORN.length; k++)
            {
                if (WORN[k] == null)
                {
                    continue;
                }
                double sx = gridLeft + WORN[k][0] * SLOT_W;
                double sy = y0 + WORN[k][1] * SLOT_H;
                g.setColor(new Color(255, 255, 255, 18));
                g.fill(new RoundRectangle2D.Double(sx + 2, sy + 1, SLOT_W - 4, SLOT_H - 2, 8, 8));
                slot(g, sx, sy, shared.equipment[k], 1);
            }
        }
        else
        {
            g.setFont(ROW);
            for (int k = 0; k < HdMapPartyGear.SKILLS; k++)
            {
                double sx = gridLeft + (k % 3) * SKILL_W;
                double sy = y0 + (k / 3) * SKILL_H;
                BufferedImage icon = images.skill(k);
                if (icon != null)
                {
                    g.drawImage(icon, (int) sx + 2, (int) sy + 3, 16, 16, null);
                }
                g.setColor(levelColor(shared.boosted[k], shared.levels[k]));
                g.drawString(level(shared.boosted[k], shared.levels[k]), (float) (sx + 22), (float) (sy + 16));
            }
        }
        paintDrops(g, row.id, x0, top + 110, panelW);
    }

    static RoundRectangle2D tab(Graphics2D g, int bottom, Clicks clicks, String label)
    {
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        int tabWidth = floorGroupWidth(metrics);
        double tabTop = bottom - 2 * CONTROL - 6;
        var tab = new RoundRectangle2D.Double(LEFT, tabTop, tabWidth, CONTROL, 10, 10);
        box(g, tab, clicks.hovered(tab) ? CONTROL_HOVER : CONTROL_FILL, CONTROL_EDGE);
        g.setColor(Color.WHITE);
        g.drawString(label, (float) (LEFT + (tabWidth - metrics.stringWidth(label)) / 2.0),
            middle(tabTop + CONTROL / 2.0, metrics));
        return tab;
    }

    void paintStatus(Graphics2D g, PartyMapMembers.Gear shared, double x, double y, double width)
    {
        if (shared == null)
        {
            return;
        }
        double gap = (width - 4 * Orbs.WIDTH) / 3.0;
        IntFunction<BufferedImage> sprites = images == null ? id -> null : images::sprite;
        int[][] orbs = orbs(shared);
        for (int k = 0; k < orbs.length; k++)
        {
            Orbs.paint(g, (int) (x + k * (Orbs.WIDTH + gap)), (int) y + 2, Orbs.Kind.values()[k], orbs[k][0], orbs[k][1],
                sprites);
        }
    }

    /** Hitpoints, prayer, run energy, special attack: value and most. */
    static int[][] orbs(PartyMapMembers.Gear shared)
    {
        int hp = Skill.HITPOINTS.ordinal();
        int prayer = Skill.PRAYER.ordinal();
        return new int[][]{{shared.boosted[hp], shared.levels[hp]}, {shared.boosted[prayer], shared.levels[prayer]},
            {shared.run, 100}, {shared.special, 100}};
    }

    void paintDrops(Graphics2D g, long id, double x0, double start, int panelW)
    {
        var showing = drops.apply(id);
        if (showing.isEmpty())
        {
            return;
        }
        long now = System.currentTimeMillis();
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        for (int k = 0; k < showing.size(); k++)
        {
            var drop = showing.get(k);
            double t = Math.min(1, (now - drop.at) / (double) PartyMapMembers.DROP_MS);
            double rise = 1 - Math.pow(1 - t, 3);
            float alpha = (float) (t < 0.7 ? 1 : 1 - (t - 0.7) / 0.3);
            String text = "+" + AMOUNT.format(drop.amount);
            double tx = x0 + panelW - 14 - metrics.stringWidth(text);
            double ty = start + (showing.size() - 1 - k) * 18 - rise * 60;
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, alpha)));
            BufferedImage icon = images == null ? null : images.skill(drop.skill);
            if (icon != null)
            {
                g.drawImage(icon, (int) tx - 19, (int) ty - 13, 16, 16, null);
            }
            g.setColor(new Color(0, 0, 0, 180));
            g.drawString(text, (float) tx + 1, (float) ty + 1);
            g.setColor(Color.WHITE);
            g.drawString(text, (float) tx, (float) ty);
            g.setComposite(old);
        }
        dropsMoving = true;
    }

    void slot(Graphics2D g, double x, double y, int id, int quantity)
    {
        if (id < 0)
        {
            return;
        }
        BufferedImage image = images.item(id, Math.max(1, quantity));
        if (image != null)
        {
            g.drawImage(image, (int) (x + (SLOT_W - image.getWidth()) / 2.0), (int) (y + (SLOT_H - image.getHeight()) / 2.0),
                null);
        }
    }

    static void dot(Graphics2D g, Color color, double x, double y)
    {
        g.setColor(color != null ? color : FRIEND);
        g.fill(new Ellipse2D.Double(x, y, 8, 8));
    }

    static void titleButton(Graphics2D g, Clicks clicks, FontMetrics title, double x, double top,
        String mark, Runnable action, String tip)
    {
        var button = new RoundRectangle2D.Double(x, top + 5, 20, 20, 8, 8);
        if (clicks.hovered(button))
        {
            g.setColor(CONTROL_HOVER);
            g.fill(button);
        }
        g.setColor(new Color(200, 200, 205));
        g.setFont(CONTROL_FONT);
        g.drawString(mark, (float) (button.getCenterX() - title.stringWidth(mark) / 2.0), (float) (top + 20));
        clicks.add(button, action, tip);
    }

    /** A skill as shown, boosted over real when they differ. */
    static String level(int now, int real)
    {
        return now == real ? String.valueOf(real) : now + "/" + real;
    }

    static Color levelColor(int now, int real)
    {
        return now > real ? BOOSTED : now < real ? DRAINED : Color.WHITE;
    }

    static String fit(String text, FontMetrics metrics, int width)
    {
        if (metrics.stringWidth(text) <= width)
        {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "…") > width)
        {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
