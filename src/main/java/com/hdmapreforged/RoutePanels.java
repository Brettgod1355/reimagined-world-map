package com.hdmapreforged;

import static com.hdmapreforged.RouteFeature.*;
import static com.hdmapreforged.SearchResults.*;
import static net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR;

import com.hdmapreforged.route.*;
import com.hdmapreforged.route.Route.*;
import com.hdmapreforged.route.Route.Step.*;
import java.awt.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;

/** The route's panels for the sidebar and the map's card: the step list, the "Now" box, and the pieces they are made of. */
final class RoutePanels
{

    static final Color SEA = new Color(64, 200, 255);
    static final Color NOTE = new Color(255, 190, 120);

    static JComponent nowBox(Route left, int width, Color walk, Color jump)
    {
        JPanel box = edgeBox(walk);
        // The text's width inside the border, with room to spare as label leaves it.
        int inner = Math.max(80, width - 47);
        box.add(text("Now", LIGHT_GRAY_COLOR, Font.PLAIN, 0, inner));
        Step now = left.steps.get(0);
        JLabel doing = text(RouteText.describe(now), color(now, walk, jump), Font.BOLD, 3, inner);
        box.add(doing);
        if (now.detail != null && !RouteText.unsure(now.detail))
        {
            box.add(text(now.detail, LIGHT_GRAY_COLOR, Font.PLAIN, 0, inner));
        }
        for (int i = 1; i < Math.min(3, left.steps.size()); i++)
        {
            Step then = left.steps.get(i);
            add(box, 4, text((i == 1 ? "Then: " : "After that: ") + RouteText.describe(then), color(then, walk, jump),
                Font.PLAIN, 0, inner));
        }
        JLabel remaining = text(RouteText.duration(left) + " left", LIGHT_GRAY_COLOR, Font.PLAIN, 0, inner);
        add(box, 4, remaining);
        var wrapped = wrapped(box, 8);
        // What changes as the player walks (updateNow).
        wrapped.putClientProperty("doing", doing);
        wrapped.putClientProperty("left", remaining);
        wrapped.putClientProperty("width", inner);
        return wrapped;
    }

    /**
     * What to bring for a quicker way, as a box like {@link #nowBox} with an orange edge: each thing in bold with what it
     * is for under it, then how much sooner.
     */
    static JComponent tipBox(List<String[]> bring, String sooner, int width)
    {
        JPanel box = edgeBox(NOTE);
        int inner = Math.max(80, width - 47);
        box.add(text("Quicker if you bring", NOTE, Font.PLAIN, 0, inner));
        for (var thing : bring)
        {
            add(box, 4, text(thing[0], Color.WHITE, Font.BOLD, 0, inner));
            box.add(text("for " + thing[1], LIGHT_GRAY_COLOR, Font.PLAIN, 0, inner));
        }
        add(box, 4, text(sooner, NOTE, Font.PLAIN, 0, inner));
        return wrapped(box, 8);
    }

    /** A box like {@link #nowBox}/{@link #tipBox}: dark background, a coloured left edge. */
    static JPanel edgeBox(Color edge)
    {
        JPanel box = column();
        box.setOpaque(true);
        box.setBackground(ColorScheme.DARK_GRAY_COLOR);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, edge),
            BorderFactory.createEmptyBorder(6, 8, 6, 6)));
        return box;
    }

    /** {@code box} sized to its content, with the gap the route panels put after a box like this. */
    static JComponent wrapped(JComponent box, int gap)
    {
        JPanel spaced = column();
        spaced.add(capped(box));
        spaced.add(Box.createVerticalStrut(gap));
        return spaced;
    }

    /** A tick of walking: only the two texts of {@link #nowBox} that change are set again. */
    static void updateNow(JComponent box, Route left)
    {
        int inner = (Integer) box.getClientProperty("width");
        setText((JLabel) box.getClientProperty("doing"), RouteText.describe(left.steps.get(0)), inner);
        setText((JLabel) box.getClientProperty("left"), RouteText.duration(left) + " left", inner);
    }

    /** The step list: title, notes, steps (click to see on the map) and "Clear path". */
    static JComponent panel(Route route, int target, String status, int width, Consumer<WorldPoint> focus,
        Runnable clear, RouteFeature actions, Color walk, Color jump, int done)
    {
        JPanel panel = column();
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        panel.add(label("Route" + (Tiles.z(target) > 0 ? " (floor " + Tiles.z(target) + ")" : ""), width,
            Font.BOLD, Color.WHITE));
        if (route == null)
        {
            panel.add(label(status != null ? status : "Searching…", width, Font.PLAIN, LIGHT_GRAY_COLOR));
        }
        else
        {
            for (String note : RouteText.notes(route))
            {
                panel.add(label(note, width, Font.PLAIN, NOTE));
            }
            boolean unreachable = actions != null && actions.unreachable();
            int blocked = unreachable ? RouteText.blockedFrom(route) : -1;
            if (unreachable)
            {
                panel.add(label(RouteText.cannotYet(route), width, Font.BOLD, CANNOT));
            }
            var blockers = actions == null || route.outcome != Outcome.NEAREST ? null
                : actions.blockers();
            if (blockers != null)
            {
                panel.add(label(blockers.isEmpty() ? "Needs something the map can't name (a quest step, a key)."
                    : blockers.contains(NO_WAY) ? NO_WAY : "Needs: " + String.join(", ", blockers), width, Font.PLAIN, NOTE));
            }
            if (!route.steps.isEmpty())
            {
                panel.add(label(RouteText.duration(route) + (route.outcome == Outcome.NEAREST
                    ? " to where the route ends" : ""), width, Font.PLAIN, LIGHT_GRAY_COLOR));
            }
            String bring = RouteText.bring(route);
            if (bring != null)
            {
                // Vines, jungle and webs do not stop the route: what to take along.
                panel.add(label("Bring: " + bring, width, Font.PLAIN, new Color(150, 220, 150)));
            }
            int number = 1;
            boolean unsure = false;
            for (Step step : route.steps)
            {
                boolean lacking = unreachable && RouteText.lacks(step);
                boolean cannot = blocked >= 0 && number - 1 >= blocked;
                String detail = lacking ? "Needs " + RouteText.lack(step) : step.detail;
                if (!lacking && RouteText.unsure(detail))
                {
                    unsure = true; // said once under the steps
                    detail = null;
                }
                // A passage, stairs or transport shows where it leads (the other map).
                boolean leads = step.isJump() && !Tiles.isSea(step.last()) && step.first() != step.last();
                int point = leads ? step.last() : step.first();
                JButton row = flat("<html><body style='width:" + Math.max(80, width - 40) + "px'>" + number++ + ". "
                    + RouteText.escape(RouteText.describe(step))
                    + (detail != null ? "<br><span style='color:" + (cannot ? "#ff7070" : "#a0a0a0") + "'>"
                        + RouteText.escape(detail) + "</span>" : "")
                    + "</body></html>", leads ? "Show where this leads on the map" : "Show on the map",
                    () -> focus.accept(worldPoint(point)));
                row.setForeground(number - 2 < done ? ColorScheme.MEDIUM_GRAY_COLOR : cannot ? CANNOT
                    : color(step, walk, jump));
                row.setMargin(new Insets(1, 0, 1, 0));
                boolean choice = step.name != null && (step.kind == Kind.TELEPORT
                    || step.kind == Kind.TRANSPORT || step.kind == Kind.SHIP
                    || step.kind == Kind.ENTRANCE && step.category != null);
                if (actions == null || !choice)
                {
                    panel.add(row);
                    continue;
                }
                JPanel line = SearchResults.panel(new BorderLayout());
                line.add(row, BorderLayout.CENTER);
                JButton more = new JButton("⋯");
                more.setFocusable(false);
                more.setMargin(new Insets(0, 4, 0, 4));
                more.setToolTipText("Leave this out of the route");
                String name = BankDetour.plain(step.name);
                String category = step.category;
                more.addActionListener(a -> {
                    var menu = new JPopupMenu();
                    String running = actions.runningTour();
                    if (running != null)
                    {
                        // Saved with the custom route: left out whenever it runs.
                        item(menu, "Leave out of \"" + running + "\"", () -> actions.avoidInRoute(name));
                        if (category != null)
                        {
                            item(menu, "Leave every " + category + " out of \"" + running + "\"",
                                () -> actions.avoidInRoute(RouteSource.TYPE + category));
                        }
                    }
                    else
                    {
                        item(menu, "Avoid for this route", () -> actions.avoid(name));
                        if (category != null)
                        {
                            item(menu, "Avoid every " + category + " for this route",
                                () -> actions.avoid(RouteSource.TYPE + category));
                        }
                    }
                    menu.addSeparator();
                    item(menu, "Never use \"" + name + "\"", () -> actions.never(name));
                    if (category != null)
                    {
                        item(menu, "Never use any " + category, () -> actions.never(RouteSource.TYPE + category));
                    }
                    menu.show(more, 0, more.getHeight());
                });
                line.add(more, BorderLayout.EAST);
                panel.add(capped(line));
            }
            if (unsure)
            {
                add(panel, 4, label("Some steps may need a quest, level or item the map does not know about.", width,
                    Font.PLAIN, ColorScheme.MEDIUM_GRAY_COLOR));
            }
        }
        if (actions != null && !actions.neverUsed().isEmpty())
        {
            add(panel, 6, label("Never used:", width, Font.BOLD, LIGHT_GRAY_COLOR));
            for (String never : actions.neverUsed())
            {
                JButton allow = flat("✕  " + Tour.describe(never), "Use it again", () -> actions.allow(never));
                allow.setForeground(LIGHT_GRAY_COLOR);
                panel.add(allow);
            }
        }
        JButton clearButton = button("Clear path", clear);
        clearButton.setAlignmentX(0);
        clearButton.setToolTipText("Remove the route from the map");
        add(panel, 4, clearButton);
        return panel;
    }

    /** The route panels' text: bold at the usual size, wrapped with room to spare (HTML comes out wider than asked). */
    static JLabel label(String text, int width, int style, Color color)
    {
        return text(text, color, style, 0, Math.max(80, width - 30));
    }

    static Color color(Step step, Color walk, Color jump)
    {
        switch (step.kind)
        {
            case WALK:
                return walk;
            case SAIL:
            case BOARD:
            case DISEMBARK:
                return SEA;
            default:
                return jump;
        }
    }
}
