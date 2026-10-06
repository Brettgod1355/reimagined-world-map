package com.hdmapreforged;

import java.awt.*;
import java.awt.image.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.gameval.*;
import net.runelite.client.ui.*;

/** The game's minimap orbs drawn from its own sprites: frame, fill to what is left, icon and coloured number. */
final class Orbs
{
    /** The frame: number box on the left, orb on the right. */
    static final int WIDTH = 57;
    static final int HEIGHT = 34;
    private static final int ORB_X = 27;
    private static final int ORB_Y = 4;
    private static final int ORB = 26;

    @RequiredArgsConstructor
    enum Kind
    {
        HITPOINTS(SpriteID.OrbFiller.HITPOINTS, SpriteID.OrbIcon.HITPOINTS, new Color(170, 40, 40)),
        PRAYER(SpriteID.OrbFiller.PRAYER, SpriteID.OrbIcon.PRAYER, new Color(40, 140, 170)),
        RUN(SpriteID.OrbFiller.RUN, SpriteID.OrbIcon.RUN, new Color(180, 150, 40)),
        SPECIAL(SpriteID.OrbFiller.SPECIAL, SpriteID.OrbIcon.SPECIAL, new Color(60, 150, 70));

        final int filler;
        final int icon;
        /** Used before the game's sprites load. */
        final Color plain;
    }

    static final int[] SPRITES = {SpriteID.OrbFrame.FRAME, SpriteID.OrbFiller.EMPTY, SpriteID.OrbFiller.HITPOINTS,
        SpriteID.OrbFiller.PRAYER, SpriteID.OrbFiller.RUN, SpriteID.OrbFiller.SPECIAL, SpriteID.OrbIcon.HITPOINTS,
        SpriteID.OrbIcon.PRAYER, SpriteID.OrbIcon.RUN, SpriteID.OrbIcon.SPECIAL};

    private Orbs()
    {
    }

    /** A negative {@code value} is unknown (empty, a dash); {@code sprites} returns null while loading. */
    static void paint(Graphics2D g, int x, int y, Kind kind, int value, int max, IntFunction<BufferedImage> sprites)
    {
        double left = value < 0 || max <= 0 ? 0 : Math.max(0, Math.min(1, value / (double) max));
        BufferedImage frame = sprites.apply(SpriteID.OrbFrame.FRAME);
        BufferedImage icon = sprites.apply(kind.icon);
        if (frame != null)
        {
            g.drawImage(frame, x, y, null);
        }
        else
        {
            g.setColor(new Color(40, 36, 30));
            g.fillRoundRect(x, y + 6, WIDTH - 4, HEIGHT - 12, 10, 10);
            g.fillOval(x + ORB_X - 3, y + ORB_Y - 3, ORB + 6, ORB + 6);
        }
        int ox = x + ORB_X;
        int oy = y + ORB_Y;
        orb(g, sprites.apply(SpriteID.OrbFiller.EMPTY), ox, oy, new Color(20, 20, 20));
        int filled = (int) Math.round(ORB * left);
        Shape clip = g.getClip();
        g.clipRect(ox, oy + ORB - filled, ORB, filled);
        orb(g, sprites.apply(kind.filler), ox, oy, kind.plain);
        g.setClip(clip);
        if (icon != null)
        {
            g.drawImage(icon, ox + (ORB - icon.getWidth()) / 2, oy + (ORB - icon.getHeight()) / 2, null);
        }
        String text = value < 0 ? "–" : String.valueOf(value);
        g.setFont(FontManager.getRunescapeSmallFont());
        FontMetrics metrics = g.getFontMetrics();
        int tx = x + 15 - metrics.stringWidth(text) / 2;
        int ty = y + 26;
        g.setColor(Color.BLACK);
        g.drawString(text, tx + 1, ty + 1);
        g.setColor(value < 0 ? Color.LIGHT_GRAY : color(left));
        g.drawString(text, tx, ty);
    }

    /** The sprite, or a plain circle while it loads. */
    private static void orb(Graphics2D g, BufferedImage sprite, int x, int y, Color plain)
    {
        if (sprite != null)
        {
            g.drawImage(sprite, x, y, ORB, ORB, null);
        }
        else
        {
            g.setColor(plain);
            g.fillOval(x, y, ORB, ORB);
        }
    }

    /** Green when full, through yellow, to red when empty. */
    static Color color(double left)
    {
        double f = Math.max(0, Math.min(1, left));
        int red = f > 0.5 ? (int) Math.round(255 * (1 - f) * 2) : 255;
        int green = f > 0.5 ? 255 : (int) Math.round(255 * f * 2);
        return new Color(red, green, 0);
    }
}
