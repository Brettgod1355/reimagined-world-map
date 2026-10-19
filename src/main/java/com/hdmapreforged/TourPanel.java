package com.hdmapreforged;

import static com.hdmapreforged.SearchResults.*;

import java.awt.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;

/** The "Custom routes" panel, where the search results show: pick, name, order and run the player's routes. Swing thread. */
@RequiredArgsConstructor
final class TourPanel
{
    interface Actions
    {
        List<Tour> tours();

        String editing();

        void save(List<Tour> tours, String editing);

        void run(Tour tour);

        /**
         * Saves the fastest order if the stops are unchanged, then runs {@code done} even on failure or cancel. With
         * {@code everything}, as if the player had every teleport, transport, level and quest in the game.
         */
        void fastestOrder(Tour tour, boolean everything, Runnable done);

        /** A short message to the player. */
        default void say(String text)
        {
        }

        /** Puts text on the clipboard; whether it could (another program may hold the clipboard). */
        default boolean copy(String text)
        {
            try
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                return true;
            }
            catch (IllegalStateException e)
            {
                return false;
            }
        }

        /** The clipboard's text, or null. */
        default String paste()
        {
            try
            {
                var held = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                return held != null && held.isDataFlavorSupported(DataFlavor.stringFlavor)
                    ? (String) held.getTransferData(DataFlavor.stringFlavor) : null;
            }
            catch (UnsupportedFlavorException | IOException | IllegalStateException e)
            {
                return null;
            }
        }

        default void cancelOrder()
        {
        }

        String running();

        void stop();
    }

    final Actions actions;
    final Consumer<IntFunction<JComponent>> show;
    final Consumer<WorldPoint> focus;
    @Getter
    boolean open;
    /** While the fastest order is worked out; {@link #orderings} counts runs, so a cancelled one ends nothing. */
    boolean ordering;
    int orderings;

    final String back;

    TourPanel(Actions actions, Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus)
    {
        this(actions, show, focus, null);
    }

    void toggle()
    {
        if (open)
        {
            close();
        }
        else
        {
            open = true;
            refresh();
        }
    }

    void close()
    {
        open = false;
        cancelOrder();
        show.accept(null);
    }

    /** A search took the card: a fastest order being worked out goes on and is saved. */
    void replaced()
    {
        open = false;
    }

    void cancelOrder()
    {
        if (ordering)
        {
            ordering = false;
            orderings++;
            actions.cancelOrder();
        }
    }

    void refresh()
    {
        if (open)
        {
            show.accept(this::section);
        }
    }

    JComponent section(int width)
    {
        JPanel panel = column();
        var tours = new ArrayList<Tour>(actions.tours());
        Tour current = Tour.named(tours, actions.editing());

        if (back != null)
        {
            panel.add(tip(link(back, this::close, width), "Back to the list"));
            panel.add(Box.createVerticalStrut(4));
        }

        var which = new JComboBox<String>(tours.stream().map(t -> t.name).toArray(String[]::new));
        which.setSelectedItem(current.name);
        which.addActionListener(e -> {
            Object chosen = which.getSelectedItem();
            if (chosen != null && !chosen.equals(current.name))
            {
                cancelOrder();
                save(tours, chosen.toString());
            }
        });
        which.setMaximumSize(new Dimension(Math.max(100, width - 60), which.getPreferredSize().height));
        panel.add(row(which, button("New", () -> {
            if (tours.size() < Tour.MAX_TOURS)
            {
                String name = Tour.unique(tours, "Route " + (tours.size() + 1));
                tours.add(new Tour(name, new ArrayList<>()));
                save(tours, name);
            }
        })));
        JTextField name = new JTextField(current.name, 14);
        name.setToolTipText("The route's name: type and press Enter");
        FullMapWindow.typable(name);
        name.addActionListener(e -> {
            String typed = name.getText().trim();
            if (!typed.isEmpty() && !typed.equals(current.name))
            {
                var others = new ArrayList<Tour>(tours);
                others.remove(current);
                String unique = Tour.unique(others, typed);
                tours.set(tours.indexOf(current), current.renamed(unique));
                save(tours, unique);
            }
        });
        // The fastest order's answer finds its route by name: renaming meanwhile would drop it.
        name.setEnabled(!ordering);
        name.setMaximumSize(new Dimension(Math.max(100, width - 60), name.getPreferredSize().height));
        JButton delete = button("Delete", () -> {
            tours.remove(current);
            save(tours, tours.isEmpty() ? "My route" : tours.get(0).name);
        });
        // Not while the fastest order is worked out; moving or removing stops neither.
        delete.setEnabled(!ordering);
        add(panel, 4, row(name, delete));
        panel.add(Box.createVerticalStrut(8));

        if (current.stops.isEmpty())
        {
            panel.add(text("No stops yet. Right-click the map, Add to custom route, or + Stop in a card.",
                ColorScheme.LIGHT_GRAY_COLOR, width));
        }
        for (int i = 0; i < current.stops.size(); i++)
        {
            int at = i;
            var stop = current.stops.get(i);
            JPanel line = panel(new BorderLayout(4, 0));
            String label = (i + 1) + ". " + stop.name;
            line.add(current.repeats(i) ? tip(text(label, ColorScheme.MEDIUM_GRAY_COLOR, width - 90),
                "The same as the stop before it: skipped, unless you move it elsewhere")
                : stop.isKind() ? text(label, Color.WHITE, width - 90)
                : link(label, () -> focus.accept(stop.point), width - 90), BorderLayout.CENTER);
            JPanel moves = panel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            moves.add(small("↑", () -> move(tours, current, at, -1), "Earlier", i > 0));
            moves.add(small("↓", () -> move(tours, current, at, 1), "Later", i < current.stops.size() - 1));
            moves.add(small("✕", () -> {
                current.stops.remove(at);
                save(tours, current.name);
            }, "Remove this stop", true));
            line.add(moves, BorderLayout.EAST);
            panel.add(capped(line));
            panel.add(Box.createVerticalStrut(2));
        }
        JPanel go = row();
        String running = actions.running();
        if (running != null)
        {
            go.add(button("Stop " + running, () -> {
                actions.stop();
                refresh();
            }));
        }
        else if (!current.stops.isEmpty())
        {
            go.add(button("Run", () -> {
                actions.run(current);
                refresh();
            }));
        }
        if (current.stops.size() > 1)
        {
            go.add(Box.createHorizontalStrut(4));
            var loop = new JCheckBox("Loop", current.loop);
            loop.setOpaque(false);
            loop.setToolTipText("After the last stop, start again at the first");
            loop.addActionListener(e -> {
                tours.set(tours.indexOf(current), current.looping(loop.isSelected()));
                save(tours, current.name);
            });
            go.add(loop);
        }
        add(panel, 8, go);
        if (current.stops.size() > 1)
        {
            JButton withItems = button(ordering ? "Working it out…" : "Order with current items",
                () -> order(current, false));
            withItems.setEnabled(!ordering);
            JButton fastest = button("Order fastest", () -> order(current, true));
            fastest.setEnabled(!ordering);
            add(panel, 4, row(tip(withItems, "Put the stops in the quickest order from where you are, with the teleports, "
                + "transport and items you have now; a home or minigame teleport is used once at most"),
                tip(fastest, "The quickest order with every teleport and transport in the game, whatever you "
                + "have; a home or minigame teleport is used once at most")));
        }
        JButton export = choice("Export", what -> {
            item(what, "This route", () -> copy(Tour.share(List.of(current)), "\"" + current.name + "\""));
            item(what, "All routes", () -> copy(Tour.share(tours), tours.size() + " routes"));
        });
        export.setEnabled(!current.stops.isEmpty());
        JButton doImport = button("Import", () -> importRoutes(tours));
        doImport.setEnabled(!ordering);
        add(panel, 4, row(tip(export, "Copy the route as text, to keep or to share"),
            tip(doImport, "Add the routes on the clipboard, as Export copies them")));

        if (!current.avoid.isEmpty())
        {
            add(panel, 8, tip(text("Leaves out:", ColorScheme.LIGHT_GRAY_COLOR, width),
                "Ways this route never takes; chosen with ⋯ at a step while the route runs"));
            for (String avoided : new ArrayList<>(current.avoid))
            {
                JPanel line = panel(new BorderLayout(4, 0));
                line.add(text(Tour.describe(avoided), Color.WHITE, width - 40), BorderLayout.CENTER);
                line.add(small("✕", () -> {
                    current.avoid.remove(avoided);
                    save(tours, current.name);
                }, "Use it in this route again", true), BorderLayout.EAST);
                panel.add(capped(line));
            }
        }
        return panel;
    }

    void order(Tour current, boolean everything)
    {
        ordering = true;
        int run = ++orderings;
        refresh();
        actions.fastestOrder(current, everything, () -> {
            if (run == orderings)
            {
                ordering = false;
                refresh();
            }
        });
    }

    void copy(String text, String what)
    {
        actions.say(actions.copy(text) ? what + " copied to the clipboard" : "Could not copy: the clipboard is in use");
    }

    /** Adds the routes with stops on the clipboard, each under a name not taken; the last added is the one edited. */
    void importRoutes(List<Tour> tours)
    {
        var found = Tour.decode(actions.paste());
        found.removeIf(t -> t.stops.isEmpty());
        if (found.isEmpty())
        {
            actions.say("Nothing to import: copy a route's text first (Export makes it)");
            return;
        }
        String last = null;
        int added = 0;
        for (Tour t : found)
        {
            if (tours.size() >= Tour.MAX_TOURS)
            {
                break;
            }
            last = Tour.unique(tours, t.name);
            tours.add(t.renamed(last));
            added++;
        }
        if (last != null)
        {
            save(tours, last);
        }
        actions.say(added < found.size() ? "Only " + Tour.MAX_TOURS + " routes can be kept: " + added + " of "
            + found.size() + " imported" : added == 1 ? "\"" + last + "\" imported" : added + " routes imported");
    }

    /** The menu last opened under a button; a test reads it, as a menu shows only under a shown button. */
    JPopupMenu lastMenu;

    /** A button that opens a menu of choices under itself, filled by {@code choices} when clicked. */
    JButton choice(String text, Consumer<JPopupMenu> choices)
    {
        var made = new JButton[1];
        made[0] = button(text, () -> {
            var menu = new JPopupMenu();
            choices.accept(menu);
            lastMenu = menu;
            if (made[0].isShowing())
            {
                menu.show(made[0], 0, made[0].getHeight());
            }
        });
        return made[0];
    }

    void move(List<Tour> tours, Tour tour, int at, int by)
    {
        int to = at + by;
        if (to < 0 || to >= tour.stops.size())
        {
            return;
        }
        tour.stops.add(to, tour.stops.remove(at));
        save(tours, tour.name);
    }

    void save(List<Tour> tours, String editing)
    {
        actions.save(tours, editing);
        refresh();
    }

    /** A row of {@code parts}, 4 pixels apart. */
    static JPanel row(JComponent... parts)
    {
        JPanel row = panel(null);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        for (var part : parts)
        {
            if (row.getComponentCount() > 0)
            {
                row.add(Box.createHorizontalStrut(4));
            }
            row.add(part);
        }
        return row;
    }

    /** Enabled when {@code usable} and not while the fastest order is worked out. */
    JButton small(String text, Runnable action, String tip, boolean usable)
    {
        JButton button = tip(button(text, action), tip);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setEnabled(usable && !ordering);
        return button;
    }

    static JLabel text(String text, Color color, int width)
    {
        return SearchResults.text(text, color, false, Math.max(60, width));
    }

    static JLabel link(String text, Runnable action, int width)
    {
        return clickable(tip(text(text, LINK, width), "Show on the map"), action, false);
    }
}
