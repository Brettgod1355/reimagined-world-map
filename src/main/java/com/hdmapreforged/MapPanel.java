package com.hdmapreforged;

import static com.hdmapreforged.SearchResults.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;

/** The sidebar: buttons to open the map, the route's steps, custom routes and the party. Swing thread. */
final class MapPanel extends PluginPanel
{
    interface Friends
    {
        boolean inGroup();

        List<FriendsWidget.Row> rows();

        /** The world the player is on, or 0. */
        int world();

        /** What a member last shared, or null. */
        PartyMapMembers.Gear gear(long id);

        FriendsWidget.Images images();

        boolean canRejoin();

        /** Rejoins the last party. */
        void join(Component from);
    }

    /** Text width inside the sidebar's padding. */
    static final int TEXT_WIDTH = PANEL_WIDTH - 52;
    private static final int FRIENDS_REFRESH_MS = 2000;

    private final Consumer<WorldPoint> showOnMap;
    private final Friends friends;
    private final Function<WorldPoint, String> placeName;
    private final JPanel routeHolder = column();
    /** Shown only with no route; a route's steps have their own title. */
    private final JLabel routeHeading = heading("Route");
    /** A route's steps not built yet: it changed while the sidebar was hidden. */
    private IntFunction<JComponent> waitingRoute;
    private final JPanel friendsHolder = column();
    private final JPanel toursHolder = column();
    private final JLabel toursHeading = heading("Custom routes");
    private TourPanel.Actions tours;
    /** The custom route open for editing, or null for the list. */
    private IntFunction<JComponent> editorSection;
    private TourPanel editor;
    private long openFriend = -1;
    private int friendPage;
    private final Timer friendsTimer;
    private String friendsShown;

    MapPanel(Runnable openMap, Runnable openWindow, Consumer<WorldPoint> showOnMap, Friends friends,
        Function<WorldPoint, String> placeName)
    {
        super(false);
        this.showOnMap = showOnMap;
        this.friends = friends;
        this.placeName = placeName;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel content = column();
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.add(tip(button("Open map", openMap), "The map over the game (also with the map key)"));
        buttons.add(tip(button("In a window", openWindow), "The map in a window of its own"));
        content.add(fitted(buttons));
        content.add(Box.createVerticalStrut(10));
        content.add(card(routeHeading, routeHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(toursHeading, toursHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(heading("Party"), friendsHolder));

        // Never wider than the sidebar: what does not fit wraps.
        JPanel top = new Fitted(new BorderLayout(), 0);
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);
        top.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // Shown (its tab picked, the sidebar opened): a route that waited is built now.
        addHierarchyListener(e -> {
            if (waitingRoute != null && isShowing())
            {
                showRoute(waitingRoute);
            }
        });
        showRoute(null);
        friendsTimer = new Timer(FRIENDS_REFRESH_MS, e -> refreshFriends());
        friendsTimer.start();
        refreshFriends();
    }

    /** The route's steps ({@code section} builds them for a width), or null for none. */
    void showRoute(IntFunction<JComponent> section)
    {
        // The steps take milliseconds to build: while the sidebar is hidden they wait, built once it shows.
        waitingRoute = section != null && !isShowing() ? section : null;
        if (waitingRoute != null)
        {
            return;
        }
        routeHolder.removeAll();
        routeHeading.setVisible(section == null);
        if (section == null)
        {
            routeHolder.add(text("No route. Right-click a place on the map, Path to here."));
        }
        else
        {
            JComponent steps = section.apply(TEXT_WIDTH);
            steps.setAlignmentX(Component.LEFT_ALIGNMENT);
            routeHolder.add(steps);
        }
        refresh(routeHolder);
    }

    void setTours(TourPanel.Actions actions)
    {
        tours = actions;
        editor = new TourPanel(actions, section -> {
            editorSection = section;
            showTours();
        }, showOnMap, "\u2039 All routes");
        refreshTours();
    }

    void refreshTours()
    {
        if (editor != null && editor.isOpen())
        {
            editor.refresh();
            return;
        }
        showTours();
    }

    private void edit(Tour tour)
    {
        tours.save(tours.tours(), tour.name);
        if (!editor.isOpen())
        {
            editor.toggle();
        }
        else
        {
            editor.refresh();
        }
    }

    private void showTours()
    {
        toursHolder.removeAll();

        if (editorSection != null)
        {
            // HTML widths come out wider than asked in RuneLite's font: some room to spare.
            JComponent section = editorSection.apply(TEXT_WIDTH - 40);
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            toursHolder.add(section);
            refresh(toursHolder);
            return;
        }
        List<Tour> saved = tours == null ? Collections.emptyList() : tours.tours();
        String running = tours == null ? null : tours.running();
        if (saved.isEmpty())
        {
            toursHolder.add(text("None yet. Right-click the map, Add to custom route."));
            JButton make = tip(button("New route", () -> edit(new Tour(Tour.unique(saved, "My route"),
                Collections.emptyList()))), "Make a route; add its stops from the map");
            make.setAlignmentX(Component.LEFT_ALIGNMENT);
            toursHolder.add(make);
        }
        for (Tour tour : saved)
        {
            boolean runs = tour.name.equals(running);
            String what = tour.stops.size() + (tour.stops.size() == 1 ? " stop" : " stops");
            toursHolder.add(entry(tour.name, runs ? "Running, " + what : what, runs ? new Color(120, 220, 140) : null,
                "Open this route: its stops, their order, Run", () -> edit(tour)));
        }
        refresh(toursHolder);
    }

    void stop()
    {
        friendsTimer.stop();
    }

    /** Rebuilt only when something shown changed. */
    private void refreshFriends()
    {
        if (!isShowing() && friendsShown != null)
        {
            return;
        }
        boolean in = friends.inGroup();
        List<FriendsWidget.Row> rows = in ? friends.rows() : Collections.emptyList();
        boolean rejoin = !in && friends.canRejoin();
        StringBuilder key = new StringBuilder(in ? "in" : rejoin ? "out, rejoin" : "out");
        String[] places = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++)
        {
            FriendsWidget.Row row = rows.get(i);
            places[i] = row.point == null ? null : placeName.apply(row.point);
            key.append('|').append(row.name).append(',').append(row.world).append(',').append(places[i]);
            if (row.id == openFriend)
            {
                PartyMapMembers.Gear shared = friends.gear(row.id);
                key.append(",open,").append(friendPage).append(',').append(shared == null ? 0 : shared.at);
            }
        }
        if (key.toString().equals(friendsShown))
        {
            return;
        }
        friendsShown = key.toString();
        friendsHolder.removeAll();
        if (!in)
        {
            if (rejoin)
            {
                JButton join = tip(button("Rejoin party", () -> friends.join(this)), "Join your last party again");
                join.setAlignmentX(Component.LEFT_ALIGNMENT);
                join.setMaximumSize(new Dimension(Integer.MAX_VALUE, join.getPreferredSize().height));
                friendsHolder.add(join);
                friendsHolder.add(Box.createVerticalStrut(4));
            }
            friendsHolder.add(text("Not in a party. In one (RuneLite's Party panel), your friends show here: where "
                + "they are and what they carry."));
        }
        else if (rows.isEmpty())
        {
            friendsHolder.add(text("No one else in your party yet."));
        }
        int me = friends.world();
        for (int i = 0; i < rows.size(); i++)
        {
            FriendsWidget.Row row = rows.get(i);
            String where = row.point == null ? "Not sharing where they are"
                : (row.world > 0 && row.world != me ? "World " + row.world + ", " : "")
                    + (places[i] != null ? places[i] : "on the map");
            friendsHolder.add(friend(row, where));
            if (row.id == openFriend)
            {
                friendsHolder.add(details(row, me));
            }
        }
        refresh(friendsHolder);
    }

    private static void refresh(JComponent c)
    {
        c.revalidate();
        c.repaint();
    }

    private void rebuildFriends()
    {
        friendsShown = null;
        refreshFriends();
    }

    private JComponent friend(FriendsWidget.Row row, String where)
    {
        return entry(row.name, where, row.color != null ? row.color : ColorScheme.MEDIUM_GRAY_COLOR,
            row.id == openFriend ? "Close" : "What " + row.name + " carries, wears and can do", () -> {
                openFriend = openFriend == row.id ? -1 : row.id;
                rebuildFriends();
            });
    }

    /** A member's details: an on-map button and their world, their orbs and one page of what they share. */
    private JComponent details(FriendsWidget.Row row, int me)
    {
        JPanel box = column();
        box.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 0));
        JPanel actions = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton show = tip(button("On map", () -> showOnMap.accept(row.point)), "Open the map where they are");
        show.setEnabled(row.point != null);
        actions.add(show);
        if (row.world > 0 && me > 0 && row.world != me)
        {
            JLabel world = new JLabel("World " + row.world, JLabel.CENTER);
            world.setToolTipText("On another world: hop there with the game's world switcher");
            actions.add(world);
        }
        box.add(fitted(actions));
        box.add(Box.createVerticalStrut(6));
        PartyMapMembers.Gear shared = friends.gear(row.id);
        if (shared == null)
        {
            box.add(text("Nothing shared yet. They share it with this plugin's setting."));
            return box;
        }
        int hp = Skill.HITPOINTS.ordinal();
        int prayer = Skill.PRAYER.ordinal();
        FriendsWidget.Images images = friends.images();
        JPanel status = new JPanel(new GridLayout(2, 2, 4, 4));
        status.add(orb(Orbs.Kind.HITPOINTS, shared.boosted[hp], shared.levels[hp], images));
        status.add(orb(Orbs.Kind.PRAYER, shared.boosted[prayer], shared.levels[prayer], images));
        status.add(orb(Orbs.Kind.RUN, shared.run, 100, images));
        status.add(orb(Orbs.Kind.SPECIAL, shared.special, 100, images));
        box.add(fitted(status));
        box.add(Box.createVerticalStrut(6));
        JPanel pages = new JPanel(new GridLayout(1, 3, 4, 0));
        for (int k = 0; k < FriendsWidget.PAGES.length; k++)
        {
            int page = k;
            JButton tab = tip(button(FriendsWidget.PAGES[k], () -> {
                friendPage = page;
                rebuildFriends();
            }), FriendsWidget.PAGES[k]);
            tab.setEnabled(k != friendPage);
            tab.setMargin(new Insets(2, 1, 2, 1));
            pages.add(tab);
        }
        box.add(fitted(pages));
        box.add(Box.createVerticalStrut(6));
        JPanel grid;
        if (friendPage == 0)
        {
            grid = new JPanel(new GridLayout(7, 4, 2, 2));
            for (int k = 0; k < HdMapPartyGear.INVENTORY; k++)
            {
                grid.add(item(images, shared.inventory[k], shared.quantities[k]));
            }
        }
        else if (friendPage == 1)
        {
            grid = new JPanel(new GridLayout(5, 3, 2, 2));
            JComponent[] cells = new JComponent[15];
            for (int k = 0; k < FriendsWidget.WORN.length; k++)
            {
                int[] at = FriendsWidget.WORN[k];
                if (at != null)
                {
                    cells[at[1] * 3 + at[0]] = item(images, shared.equipment[k], 1);
                }
            }
            for (JComponent cell : cells)
            {
                grid.add(cell != null ? cell : new JLabel());
            }
        }
        else
        {
            grid = new JPanel(new GridLayout(8, 3, 2, 2));
            for (int k = 0; k < Math.min(HdMapPartyGear.SKILLS, Skill.values().length); k++)
            {
                BufferedImage icon = images.skill(k);
                JLabel skill = new JLabel(FriendsWidget.level(shared.boosted[k], shared.levels[k]),
                    icon == null ? null : new ImageIcon(icon), JLabel.LEFT);
                skill.setForeground(FriendsWidget.levelColor(shared.boosted[k], shared.levels[k]));
                skill.setToolTipText(Skill.values()[k].getName());
                grid.add(skill);
            }
        }
        box.add(fitted(grid));
        return box;
    }

    private static JComponent item(FriendsWidget.Images images, int id, int quantity)
    {
        BufferedImage picture = id < 0 ? null : images.item(id, Math.max(1, quantity));
        JLabel label = new JLabel(picture == null ? null : new ImageIcon(picture));
        label.setHorizontalAlignment(JLabel.CENTER);
        label.setPreferredSize(new Dimension(36, 32));
        return label;
    }

    private static JComponent orb(Orbs.Kind kind, int value, int max, FriendsWidget.Images pictures)
    {
        JComponent orb = new JComponent()
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                Orbs.paint(g2, (getWidth() - Orbs.WIDTH) / 2, 0, kind, value, max, pictures::sprite);
                g2.dispose();
            }
        };
        orb.setPreferredSize(new Dimension(Orbs.WIDTH, Orbs.HEIGHT));
        return orb;
    }

    /** A name over a smaller line; with {@code action}, clickable. */
    private static JComponent entry(String title, String under, Color dot, String tip, Runnable action)
    {
        JPanel line = new JPanel(new BorderLayout(6, 0));
        line.setBackground(ColorScheme.DARK_GRAY_COLOR);
        line.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (dot != null)
        {
            JLabel mark = new JLabel("\u25CF");
            mark.setForeground(dot);
            line.add(mark, BorderLayout.WEST);
        }
        JPanel texts = new JPanel(new GridLayout(2, 1));
        texts.setOpaque(false);
        JLabel name = InfoCard.plain(title);
        name.setForeground(action != null ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
        name.setFont(FontManager.getRunescapeBoldFont());
        texts.add(name);
        JLabel small = InfoCard.plain(under);
        small.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        small.setFont(FontManager.getRunescapeSmallFont());
        texts.add(small);
        line.add(texts, BorderLayout.CENTER);
        if (action != null)
        {
            line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            line.setToolTipText(tip);
            line.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    action.run();
                }

                @Override
                public void mouseEntered(MouseEvent e)
                {
                    line.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
                }

                @Override
                public void mouseExited(MouseEvent e)
                {
                    line.setBackground(ColorScheme.DARK_GRAY_COLOR);
                }
            });
        }
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        JPanel spaced = column();
        spaced.add(line);
        spaced.add(Box.createVerticalStrut(4));
        return spaced;
    }

    private static JComponent card(JComponent heading, JComponent body)
    {
        JPanel card = column();
        card.setOpaque(true);
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        card.add(heading);
        card.add(body);
        return card;
    }

    /** Transparent, left-aligned, no taller than it needs. */
    private static JComponent fitted(JComponent c)
    {
        c.setOpaque(false);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static JLabel heading(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel text(String text)
    {
        return SearchResults.text(text, ColorScheme.LIGHT_GRAY_COLOR, Font.PLAIN, 0, TEXT_WIDTH);
    }
}
