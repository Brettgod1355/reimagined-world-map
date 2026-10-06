package com.hdmapreforged;

import static com.hdmapreforged.SearchResults.*;

import java.awt.*;
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

        /** Saves the fastest order if the stops are unchanged, then runs {@code done} even on failure or cancel. */
        void fastestOrder(Tour tour, Runnable done);

        default void cancelOrder()
        {
        }

        String running();

        void stop();
    }

    private static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;

    private final Actions actions;
    private final Consumer<IntFunction<JComponent>> show;
    private final Consumer<WorldPoint> focus;
    private boolean open;
    /** While the fastest order is worked out; {@link #orderings} counts runs, so a cancelled one ends nothing. */
    private boolean ordering;
    private int orderings;

    private final String back;

    TourPanel(Actions actions, Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus)
    {
        this(actions, show, focus, null);
    }

    boolean isOpen()
    {
        return open;
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

    private void cancelOrder()
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

    private JComponent section(int width)
    {
        JPanel panel = column();
        List<Tour> tours = new ArrayList<>(actions.tours());
        Tour current = Tour.named(tours, actions.editing());

        if (back != null)
        {
            panel.add(tip(link(back, this::close, width), "Back to the list"));
            panel.add(Box.createVerticalStrut(4));
        }

        JPanel pick = row();
        JComboBox<String> which = new JComboBox<>();
        for (Tour t : tours)
        {
            which.addItem(t.name);
        }
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
        pick.add(which);
        pick.add(Box.createHorizontalStrut(4));
        pick.add(button("New", () -> {
            if (tours.size() < Tour.MAX_TOURS)
            {
                String name = Tour.unique(tours, "Route " + (tours.size() + 1));
                tours.add(new Tour(name, new ArrayList<>()));
                save(tours, name);
            }
        }));
        panel.add(pick);
        panel.add(Box.createVerticalStrut(4));
        JPanel naming = row();
        JTextField name = new JTextField(current.name, 14);
        name.setToolTipText("The route's name: type and press Enter");
        FullMapWindow.typable(name);
        name.addActionListener(e -> {
            String typed = name.getText().trim();
            if (!typed.isEmpty() && !typed.equals(current.name))
            {
                List<Tour> others = new ArrayList<>(tours);
                others.remove(current);
                String unique = Tour.unique(others, typed);
                tours.set(tours.indexOf(current), current.renamed(unique));
                save(tours, unique);
            }
        });
        // The fastest order's answer finds its route by name: renaming meanwhile would drop it.
        name.setEnabled(!ordering);
        name.setMaximumSize(new Dimension(Math.max(100, width - 60), name.getPreferredSize().height));
        naming.add(name);
        naming.add(Box.createHorizontalStrut(4));
        JButton delete = button("Delete", () -> {
            tours.remove(current);
            save(tours, tours.isEmpty() ? "My route" : tours.get(0).name);
        });
        // Not while the fastest order is worked out; moving or removing stops neither.
        delete.setEnabled(!ordering);
        naming.add(delete);
        panel.add(naming);
        panel.add(Box.createVerticalStrut(8));

        if (current.stops.isEmpty())
        {
            panel.add(text("No stops yet. Right-click the map, Add to custom route, or + Stop in a card.",
                ColorScheme.LIGHT_GRAY_COLOR, width));
        }
        for (int i = 0; i < current.stops.size(); i++)
        {
            int at = i;
            Tour.Stop stop = current.stops.get(i);
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
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
            panel.add(line);
            panel.add(Box.createVerticalStrut(2));
        }
        panel.add(Box.createVerticalStrut(8));

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
            JButton fastest = button(ordering ? "Working it out…" : "Fastest order", () -> {
                ordering = true;
                int run = ++orderings;
                refresh();
                actions.fastestOrder(current, () -> {
                    if (run == orderings)
                    {
                        ordering = false;
                        refresh();
                    }
                });
            });
            fastest.setEnabled(!ordering);
            go.add(tip(fastest, "Let the route planner put the stops in the quickest order from where you are"));
            go.add(Box.createHorizontalStrut(4));
            JCheckBox loop = new JCheckBox("Loop", current.loop);
            loop.setOpaque(false);
            loop.setToolTipText("After the last stop, start again at the first");
            loop.addActionListener(e -> {
                tours.set(tours.indexOf(current), current.looping(loop.isSelected()));
                save(tours, current.name);
            });
            go.add(loop);
        }
        panel.add(go);
        return panel;
    }

    private void move(List<Tour> tours, Tour tour, int at, int by)
    {
        int to = at + by;
        if (to < 0 || to >= tour.stops.size())
        {
            return;
        }
        tour.stops.add(to, tour.stops.remove(at));
        save(tours, tour.name);
    }

    private void save(List<Tour> tours, String editing)
    {
        actions.save(tours, editing);
        refresh();
    }

    private static JPanel row()
    {
        JPanel row = panel(null);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        return row;
    }

    /** Enabled when {@code usable} and not while the fastest order is worked out. */
    private JButton small(String text, Runnable action, String tip, boolean usable)
    {
        JButton button = tip(button(text, action), tip);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setEnabled(usable && !ordering);
        return button;
    }

    private static JLabel text(String text, Color color, int width)
    {
        return SearchResults.text(text, color, false, Math.max(60, width));
    }

    private static JLabel link(String text, Runnable action, int width)
    {
        return clickable(tip(text(text, LINK, width), "Show on the map"), action, false);
    }
}
