package com.hdmapreforged;

import com.google.common.collect.Lists;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;

/** The map with its controls and detail card: in a window of its own, or over the game. */
final class MapScreen extends JPanel implements MapView.Listener
{
    enum Layout
    {
        WINDOW,
        /** Over the game: floating controls and card; the search field takes keys only once clicked. */
        FULL
    }

    private static final int MAX_RESULTS = 20;
    private static final int NEAREST = 10;
    private static final int FLOATING_WIDTH = 290;
    /** The search's panel is wider: its lists hold pictures and places with their words. */
    private static final int SEARCH_WIDTH = 380;
    private static final int SEARCH_TEXT = 195 + SEARCH_WIDTH - FLOATING_WIDTH;
    private static final int FLOATING_TEXT = 195;

    private final MapView view;
    private final InfoCard card;
    private final JScrollPane cardScroll;
    private final Floating cardFloat = new Floating("Details", () -> showCard(false));
    /** Closed, the search's arrows and labels leave the map too. */
    private final Floating npcFloat = new Floating("Search", () -> clearSearch());
    private final Floating toursFloat = new Floating("Custom routes", () -> {
        if (this.tours != null)
        {
            this.tours.close();
        }
    });
    private final JTextField search = new HintField(() -> this.mode.hint);
    private final JComboBox<BaseMap> maps = new JComboBox<>();
    private final JComboBox<String> floors = new JComboBox<>(new String[]{"Floor 0", "Floor 1", "Floor 2", "Floor 3"});
    private final JToggleButton follow = new JToggleButton(new ImageIcon(PoiIcons.followIcon()));
    private final JButton popOut = new JButton();
    private final JButton zoomIn;
    private final JButton zoomOut;
    private final JPanel controls = new JPanel(new GridBagLayout());
    private JSplitPane split;
    private boolean syncing;
    /** Null until the window or the game-view map shows this screen. */
    private Layout layout;

    private final HdMapReforgedConfig config;
    private final Supplier<WorldData> world;
    @RequiredArgsConstructor
    enum SearchMode
    {
        ALL("Search places, monsters, items…", "Searching everything. Click to search places only"),
        PLACES("Search places, patches, spots…", "Searching places, teleports, maps and every place of a kind (herb "
            + "patches, shark fishing spots, banks). Click to search monsters and NPCs only"),
        MONSTERS("Find a monster or NPC…", "Searching monsters and NPCs. Click to search items only"),
        ITEMS("Find an item…", "Searching items in shops. Click to search everything");

        final String hint;
        final String tooltip;

        SearchMode next()
        {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private SearchMode mode = SearchMode.ALL;
    private final JButton searchMode = new JButton();

    private final SearchResults npcs;
    /** What the search results last put in the card or their panel. */
    private IntFunction<JComponent> searchSection;

    private final JLayeredPane layers = new JLayeredPane();

    MapScreen(TileCache tiles, HdMapReforgedConfig config, Supplier<WorldData> world, ItemNames itemNames,
        Runnable togglePopOut)
    {
        super(new BorderLayout());
        this.config = config;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        view = new MapView(tiles, config);
        view.setListener(this);
        card = new InfoCard(view, world, itemNames, config);
        this.world = world;
        npcs = new SearchResults(view, world, new SearchResults.Card()
        {
            @Override
            public void show(IntFunction<JComponent> section)
            {
                searchSection = section;
                if (tours != null && layout != Layout.FULL)
                {
                    tours.replaced();
                }
                showSection(section);
            }

            @Override
            public void update(IntFunction<JComponent> section)
            {
                // Late answers never reopen or replace what the user opened since (a place's details, a route).
                if (searchSection == null)
                {
                    return;
                }
                if (layout == Layout.FULL)
                {
                    if (npcFloat.isVisible())
                    {
                        searchSection = section;
                        showFloating(npcPanel, npcFloat, section, JLayeredPane.PALETTE_LAYER);
                    }
                }
                else if (card.updateExtra(searchSection, section))
                {
                    searchSection = section;
                }
            }
        });
        npcs.setItemPictures(itemNames::picture);
        npcs.setPrices(itemNames);
        card.setItemSearch(npcs::findItem);
        npcs.setPlaceNames(this::place);
        cardScroll = scroll(card);
        for (Floating floating : new Floating[]{cardFloat, npcFloat, toursFloat, houseFloat})
        {
            floating.addPropertyChangeListener("folded", e -> placeFloatingCard());
        }

        search.setToolTipText("Type and press Enter. The button next to it switches between places, monsters and items");
        FullMapWindow.typable(search);
        search.addActionListener(e -> chooseFirst());
        // The arrow keys move through the suggestions; Escape closes them, then clears what was typed.
        HintField.bind(search, KeyEvent.VK_DOWN, "down", () -> move(1), () -> true);
        HintField.bind(search, KeyEvent.VK_UP, "up", () -> move(-1), () -> true);
        HintField.bind(search, KeyEvent.VK_ESCAPE, "leave", () -> {
            if (results != null && results.isVisible())
            {
                results.setVisible(false);
                highlighted = -1;
            }
            else
            {
                search.setText("");
            }
        }, () -> results != null && results.isVisible() || !search.getText().isEmpty());
        // Clicked again: what the field holds shows at once, not only after more typing.
        search.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (results == null || !results.isVisible())
                {
                    showResults();
                }
            }
        });
        HintField.onEdit(search, typing::restart);
        typing.setRepeats(false);
        plain(searchMode, null, 3, e -> keepFocus(() -> {
            mode = mode.next();
            updateSearchMode();
            showResults();
        }));
        updateSearchMode();
        maps.setToolTipText("Map: the surface, a dungeon or another area");
        maps.addActionListener(e -> {
            BaseMap chosen = (BaseMap) maps.getSelectedItem();
            if (!syncing && chosen != null && chosen != view.map())
            {
                openMap(chosen);
            }
        });
        floors.setToolTipText("Floor (plane)");
        floors.addActionListener(e -> {
            if (!syncing)
            {
                view.choosePlane(floors.getSelectedIndex());
            }
        });
        plain(follow, "Follow: keep your character in view", 4, e -> {
            if (follow.isSelected())
            {
                view.centerOnPlayer();
            }
            else
            {
                view.setFollowing(false);
            }
        });
        plain(popOut, null, 4, e -> togglePopOut.run());
        card.setOnRebuilt(this::placeFloatingCard);
        layers.addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                placeFloatingCard();
            }
        });
        zoomIn = plain(new JButton("+"), "Zoom in", 6, e -> view.zoomBy(1));
        zoomOut = plain(new JButton("−"), "Zoom out", 6, e -> view.zoomBy(-1));

        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        viewChanged();
    }

    private static <B extends AbstractButton> B plain(B button, String tip, int side, ActionListener action)
    {
        button.setToolTipText(tip);
        button.setFocusable(false);
        button.setMargin(new Insets(1, side, 1, side));
        button.addActionListener(action);
        return button;
    }

    /** Never takes focus from the game. */
    private void keepFocus(Runnable action)
    {
        boolean typing = search.isFocusOwner();
        action.run();
        if (typing)
        {
            search.requestFocusInWindow();
        }
    }

    void setScreenLayout(Layout layout)
    {
        this.layout = layout;
        removeAll();
        layers.removeAll();
        if (split != null)
        {
            split.removeAll();
            split = null;
        }
        view.setChrome(layout == Layout.FULL ? this::showMapPicker : null);
        if (layout == Layout.FULL)
        {
            card.setTextWidth(FLOATING_TEXT);
            layers.add(view, JLayeredPane.DEFAULT_LAYER);
            cardFloat.setContent(cardScroll);
            for (JComponent part : new JComponent[]{cardFloat, search, searchMode})
            {
                layers.add(part, JLayeredPane.PALETTE_LAYER);
            }
            cardFloat.setVisible(view.selected() != null || card.hasRoute() || card.hasExtra());
            add(layers, BorderLayout.CENTER);
            placeFloatingCard();
        }
        else
        {
            cardScroll.setVisible(true);
            controls.removeAll();
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2, 2, 2, 2);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.gridy = 0;
            // One row: search, map, floor, zoom, follow and the button that closes the window.
            JComponent[] parts = {search, searchMode, maps, floors, zoomOut, zoomIn, follow, popOut};
            double[] weight = {1, 0, 0.6, 0, 0, 0, 0, 0};
            for (int i = 0; i < parts.length; i++)
            {
                c.weightx = weight[i];
                controls.add(parts[i], c);
            }
            popOut.setText("Dock");
            popOut.setToolTipText("Close this window; the sidebar's button opens it again");
            maps.setPrototypeDisplayValue(new BaseMap(0, "Gielinor Surface", 0, 0, 0, 0, 0, 0));
            add(controls, BorderLayout.NORTH);
            split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, view, cardScroll);
            split.setBorder(BorderFactory.createEmptyBorder());
            split.setContinuousLayout(true);
            split.setResizeWeight(0.78);
            cardScroll.setPreferredSize(new Dimension(300, 400));
            card.setTextWidth(250);
            add(split, BorderLayout.CENTER);
        }
        revalidate();
        repaint();
    }

    /** Null until the route feature is set up. */
    private TourPanel tours;

    void setTours(TourPanel.Actions actions)
    {
        boolean first = tours == null;
        tours = actions == null ? null : new TourPanel(actions, this::showTours, view::focus);
        if (first && tours != null)
        {
            // Only once the window or the game-view map shows this screen.
            view.addWidget(new RoutesButton(this::toggleTours, () -> tours != null && tours.isOpen(),
                () -> layout != null));
        }
    }

    /** Not before the window or the game-view map first shows this screen. */
    private void toggleTours()
    {
        if (tours != null && layout != null)
        {
            tours.toggle();
        }
    }

    void openTours()
    {
        if (tours != null && tours.isOpen() && layout != null)
        {
            refreshTours();
        }
        else
        {
            toggleTours();
        }
    }

    void refreshTours()
    {
        if (tours != null)
        {
            tours.refresh();
        }
    }

    private final JPanel toursPanel = new JPanel(new BorderLayout());

    /** A panel of its own over the game in {@link Layout#FULL}; elsewhere the card's extra section. */
    private void showFloatingElseSection(JPanel panel, Floating floating, int layer, IntFunction<JComponent> section)
    {
        if (layout == Layout.FULL)
        {
            showFloating(panel, floating, section, layer);
        }
        else
        {
            showSection(section);
        }
    }

    /** Over the game in their own panel beside the Routes button; elsewhere in the card. */
    private void showTours(IntFunction<JComponent> section)
    {
        // Above the card and search results, which could otherwise steal its clicks on a narrow map.
        showFloatingElseSection(toursPanel, toursFloat, JLayeredPane.PALETTE_LAYER + 1, section);
    }

    private void clearSearch()
    {
        npcs.clear();
    }

    private void showSection(IntFunction<JComponent> section)
    {
        if (layout == Layout.FULL)
        {
            showFloating(npcPanel, npcFloat, section, JLayeredPane.PALETTE_LAYER);
            return;
        }
        card.setExtra(section);
        showCardAtTop(section != null || view.selected() != null || card.hasRoute());
    }

    private final JPanel npcPanel = new JPanel(new BorderLayout());
    private final JPanel housePanel = new JPanel(new BorderLayout());
    private final Floating houseFloat = new Floating("Your house", () -> showHouse(null));

    /** The "Your house" panel: over the game in a panel of its own, elsewhere in the card; null closes it. */
    void showHouse(IntFunction<JComponent> section)
    {
        showFloatingElseSection(housePanel, houseFloat, JLayeredPane.PALETTE_LAYER + 1, section);
    }

    /** The "Your house" panel built again, only where it shows. */
    void updateHouse(IntFunction<JComponent> section)
    {
        if (layout != Layout.FULL)
        {
            card.updateExtra(section, section);
        }
        else if (houseFloat.getParent() != null && houseFloat.isVisible())
        {
            // Only one the user opened: a panel never shown yet counts as visible in Swing.
            showHouse(section);
        }
    }

    /**
     * A panel of its own over the game, in a scroll pane made on first use. The search panel scrolls its list only:
     * its title, buttons and tabs stay in view.
     */
    private void showFloating(JPanel panel, Floating floating, IntFunction<JComponent> section, Integer layer)
    {
        boolean search = panel == npcPanel;
        if (panel.getParent() == null)
        {
            panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            floating.setContent(search ? panel : scroll(panel));
        }
        panel.removeAll();
        if (section != null)
        {
            JComponent built = section.apply(search ? SEARCH_TEXT : FLOATING_TEXT);
            boolean parts = built instanceof SearchResults.Sections;
            panel.add(parts ? ((SearchResults.Sections) built).scrolling(MapScreen::scroll) : built,
                parts ? BorderLayout.CENTER : BorderLayout.NORTH);
        }
        if (floating.getParent() != layers)
        {
            layers.add(floating, layer);
        }
        floating.setVisible(section != null);
        placeFloatingCard();
        panel.revalidate();
        layers.repaint();
    }

    private static JScrollPane scroll(JComponent content)
    {
        JScrollPane scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void placeFloatingCard()
    {
        if (layout != Layout.FULL)
        {
            return;
        }
        int w = layers.getWidth();
        int h = layers.getHeight();
        view.setBounds(0, 0, w, h);
        // Clear of the zoom and follow buttons bottom right.
        int height = cardFloat.height(Math.max(120, Math.min(card.getPreferredSize().height + 4, h - 58 - 170)));
        cardFloat.setBounds(w - FLOATING_WIDTH - 12, 58, FLOATING_WIDTH, height);
        int searchWidth = Math.min(260, Math.max(120, w / 4));
        search.setBounds(12, 52, searchWidth, 28);
        searchMode.setBounds(12 + searchWidth + 4, 52, 28, 28);
        if (toursFloat.isVisible())
        {
            int bottomY = MapView.widgetBottom(h);
            int toursHeight = fit(toursFloat, toursPanel, bottomY - 60);
            toursFloat.setBounds(20 + MapView.floorGroupWidth(view.getFontMetrics(MapView.CONTROL_FONT)),
                bottomY - toursHeight, FLOATING_WIDTH, toursHeight);
            toursFloat.revalidate();
        }
        for (Floating floating : new Floating[]{npcFloat, houseFloat})
        {
            if (floating.isVisible())
            {
                floating.setBounds(12, 90, floating == npcFloat ? SEARCH_WIDTH : FLOATING_WIDTH,
                    fit(floating, floating == npcFloat ? npcPanel : housePanel, h - 90 - 70));
                floating.revalidate();
            }
        }
        cardFloat.revalidate();
    }

    private static int fit(Floating floating, JPanel panel, int room)
    {
        return floating.height(Math.max(90, Math.min(panel.getPreferredSize().height + 20, room - Floating.BAR)));
    }

    private void showMapPicker(Point at)
    {
        JPopupMenu menu = new JPopupMenu();
        BaseMap mine = myMap();
        if (mine != null)
        {
            menu.add(mapItem("Where I am: " + mine.name, mine));
        }
        List<BaseMap> rest = new ArrayList<>();
        for (BaseMap map : allMaps())
        {
            if (map.id == BaseMap.SURFACE || map.id == BaseMap.FULL)
            {
                menu.add(mapItem(map.name, map));
            }
            else
            {
                rest.add(map);
            }
        }
        menu.addSeparator();
        for (String range : new String[]{"A–C", "D–F", "G–K", "L–O", "P–S", "T–Z"})
        {
            JMenu sub = new JMenu(range);
            char from = range.charAt(0);
            char to = range.charAt(2);
            for (BaseMap map : rest)
            {
                char first = Character.toUpperCase(map.name.charAt(0));
                if (first >= from && first <= to || range.equals("T–Z") && !Character.isLetter(first))
                {
                    sub.add(mapItem(map.name, map));
                }
            }
            if (sub.getItemCount() > 0)
            {
                menu.add(sub);
            }
        }
        menu.show(view, at.x, at.y);
    }

    private JMenuItem mapItem(String label, BaseMap map)
    {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> openMap(map));
        return item;
    }

    private void showCard(boolean show)
    {
        if (layout == Layout.FULL)
        {
            cardFloat.setVisible(show);
            placeFloatingCard();
            layers.repaint();
        }
    }

    /** Shows or hides the card, scrolled back to the top: for content just set on it, not a route still running. */
    private void showCardAtTop(boolean show)
    {
        showCard(show);
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    void opened()
    {
        // On a route: its steps show whenever the map opens, not only when the route was asked for.
        if (card.hasRoute())
        {
            if (view.selected() != null)
            {
                view.select(null);
            }
            card.showRoute();
            showCard(true);
        }
        view.setFollowing(false);
        if (view.player() != null && config.followPlayer())
        {
            view.jumpToPlayer();
        }
    }

    /** What {@link #setData} gave, with the place search and the kinds of places. */
    private MapData data;

    void setData(BaseMaps baseMaps, MapData data)
    {
        Poi previous = view.selected();
        syncing = true;
        maps.setModel(new DefaultComboBoxModel<>(baseMaps.all().toArray(new BaseMap[0])));
        syncing = false;
        view.setMaps(baseMaps);
        view.setPois(data.pois, data.hidden);
        view.setLabels(data.labels);
        view.select(previous == null ? null : data.pois.stream()
            .filter(poi -> poi.type == previous.type && poi.location.equals(previous.location)).findFirst().orElse(null));
        viewChanged();
        this.data = data;
    }

    void setUnlocks(Unlocks unlocks)
    {
        view.setUnlocks(unlocks);
        card.refresh();
    }

    void setFollowing(boolean following)
    {
        view.setFollowing(following);
    }

    @Override
    public void nearestRequested(WorldPoint point)
    {
        BaseMaps baseMaps = view.maps();
        BaseMap target = baseMaps == null ? null : baseMaps.find(point.getX(), point.getY());
        List<Poi> found = new ArrayList<>();
        for (Poi poi : view.pois())
        {
            boolean arrives = poi.type == PoiType.TELEPORT || poi.type.layer == Layer.TRANSPORTS && !poi.links().isEmpty()
                || poi.nearby().stream().anyMatch(p -> p.type == PoiType.TELEPORT);
            if (arrives && poi.map != null && poi.map == target && view.usable(poi)
                && (poi.type == PoiType.TELEPORT ? config.showTeleports() : config.showTransports()))
            {
                found.add(poi);
            }
        }
        found.sort(Comparator.comparingInt(p -> p.location.distanceTo2D(point)));
        card.showNearest(point, found.subList(0, Math.min(NEAREST, found.size())));
        showCardAtTop(true);
    }

    void setNpcPictures(NpcPictures npcPictures)
    {
        npcs.setNpcPictures(npcPictures);
        card.setNpcPictures(npcPictures);
    }

    void setRouter(Consumer<WorldPoint> router)
    {
        card.setRouter(router);
    }

    /** Shows a place of the search's result, as a click on it in the list does. */
    void lookAtFound(WorldPoint place)
    {
        npcs.lookAtPoint(place);
    }

    /** Finds the fastest of a search's places and shows it. */
    void setFastest(Consumer<List<WorldPoint>> fastest)
    {
        npcs.setFastest(fastest);
    }

    void setStopAdder(Consumer<Tour.Stop> adder)
    {
        card.setStopAdder(adder);
        npcs.setStopAdder(adder);
    }

    void showToast(String text)
    {
        view.showToast(text);
    }

    /** A kind of place by its label, from the data shown: never waits for the search index. */
    KindIndex.Kind kind(String label)
    {
        return data == null ? null : data.kinds.all().stream().filter(kind -> kind.label.equalsIgnoreCase(label))
            .findFirst().orElse(null);
    }

    void setWindowControls(MapView.WindowControls controls)
    {
        view.setWindowControls(controls);
    }

    MapView view()
    {
        return view;
    }

    /** {@code bringUp}: show in front of a selected icon, for a route the user just asked for. */
    void showRoute(IntFunction<JComponent> route, boolean bringUp)
    {
        if (bringUp && route != null && view.selected() != null)
        {
            view.select(null);
        }
        card.setRoute(route, bringUp);
        if (bringUp || route == null)
        {
            showCard(view.selected() != null || route != null);
        }
        view.repaint();
    }

    void setPlayer(WorldPoint player)
    {
        view.setPlayer(player);
    }

    void refresh()
    {
        view.repaint();
    }

    void stop()
    {
        typing.stop();
        view.stop();
    }

    @Override
    public void selectionChanged(Poi poi)
    {
        card.show(poi);
        showCardAtTop(poi != null || card.hasRoute() || card.hasExtra());
    }

    @Override
    public void viewChanged()
    {
        syncing = true;
        if (view.map() != null && maps.getSelectedItem() != view.map())
        {
            maps.setSelectedItem(view.map());
        }
        if (floors.getSelectedIndex() != view.plane())
        {
            floors.setSelectedIndex(view.plane());
        }
        follow.setSelected(view.isFollowing());
        syncing = false;
    }

    private BaseMap myMap()
    {
        WorldPoint me = view.player();
        BaseMaps baseMaps = view.maps();
        return me == null || baseMaps == null ? null : baseMaps.find(me);
    }

    private void openMap(BaseMap map)
    {
        view.setFollowing(false);
        view.rememberForBack(map);
        if (myMap() == map)
        {
            view.showMap(map, MapView.shownOn(map, view.player()), MapView.PLAYER_ZOOM);
        }
        else
        {
            view.showMap(map, null, fitZoom(map));
        }
    }

    private double fitZoom(BaseMap map)
    {
        double width = Math.max(100, view.getWidth());
        double height = Math.max(100, view.getHeight());
        // Broken bounds must not make the zoom infinite or NaN.
        double fit = Math.min(width / Math.max(1, map.maxX - map.minX), height / Math.max(1, map.maxY - map.minY));
        double zoom = Math.log(fit) / Math.log(2);
        return Double.isFinite(zoom) ? Math.max(MapView.MIN_ZOOM, Math.min(2, zoom)) : MapView.PLAYER_ZOOM;
    }

    private void updateSearchMode()
    {
        searchMode.setIcon(new ImageIcon(mode == SearchMode.MONSTERS ? SearchResults.dotIcon()
            : mode == SearchMode.ITEMS ? SearchResults.itemIcon() : mode == SearchMode.PLACES ? PoiIcons.pinIcon()
            : SearchResults.allIcon()));
        searchMode.setToolTipText(mode.tooltip);
        search.repaint();
    }

    /** Results while typing only after a short pause. */
    private final Timer typing = new Timer(250, e -> showResults());
    private JPopupMenu results;

    private void showResults()
    {
        String typed = search.getText().trim();
        if (results != null)
        {
            results.setVisible(false);
            results = null;
        }
        if (typed.length() < 2 || !search.isShowing())
        {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        if (mode == SearchMode.ALL)
        {
            // A few of each kind, under a heading that searches that kind alone.
            for (SearchMode kind : new SearchMode[]{SearchMode.PLACES, SearchMode.MONSTERS, SearchMode.ITEMS})
            {
                List<JMenuItem> found = found(kind, typed, MAX_EACH);
                if (!found.isEmpty())
                {
                    menu.add(heading(kind));
                    found.forEach(menu::add);
                }
            }
            if (menu.getComponentCount() == 0)
            {
                menu.add(disabled("Nothing found"));
            }
        }
        else
        {
            List<JMenuItem> found = found(mode, typed, MAX_RESULTS);
            found.forEach(menu::add);
            if (found.isEmpty())
            {
                menu.add(disabled(mode == SearchMode.PLACES ? "Nothing found" : "Not in the lists"));
            }
        }
        menu.setFocusable(false);
        results = menu;
        highlighted = -1;
        keepFocus(() -> menu.show(search, 0, search.getHeight()));
    }

    /** The suggestion the arrow keys moved to, lit in the popup; -1 for none. */
    private int highlighted = -1;

    /** The suggestions that can be chosen: not the kinds' headings, nor "Nothing found". */
    private List<JMenuItem> choosable()
    {
        List<JMenuItem> items = new ArrayList<>();
        if (results != null && results.isVisible())
        {
            for (Component item : results.getComponents())
            {
                if (item instanceof JMenuItem && item.isEnabled()
                    && !Boolean.TRUE.equals(((JMenuItem) item).getClientProperty("heading")))
                {
                    items.add((JMenuItem) item);
                }
            }
        }
        return items;
    }

    /** Lights the suggestion {@code by} places on from the lit one, round the ends. */
    private void move(int by)
    {
        if (results == null || !results.isVisible())
        {
            showResults();
        }
        List<JMenuItem> items = choosable();
        if (items.isEmpty())
        {
            return;
        }
        highlighted = highlighted < 0 ? (by > 0 ? 0 : items.size() - 1)
            : ((highlighted + by) % items.size() + items.size()) % items.size();
        for (int i = 0; i < items.size(); i++)
        {
            items.get(i).setArmed(i == highlighted);
        }
    }

    /** Enter: the suggestion lit by the arrow keys, else the first that can be chosen. */
    private void chooseFirst()
    {
        typing.stop();
        if (results == null || !results.isVisible())
        {
            showResults();
        }
        JPopupMenu menu = results;
        List<JMenuItem> items = choosable();
        if (menu == null || items.isEmpty())
        {
            return;
        }
        JMenuItem chosen = items.get(highlighted >= 0 && highlighted < items.size() ? highlighted : 0);
        menu.setVisible(false);
        chosen.doClick(0);
    }

    private static final int MAX_GAME_ICONS = 8;

    /** Each kind's best few when searching everything. */
    private static final int MAX_EACH = 8;

    private List<JMenuItem> found(SearchMode kind, String typed, int max)
    {
        return kind == SearchMode.PLACES ? placeResults(typed.toLowerCase(Locale.ROOT), max)
            : worldResults(typed, kind == SearchMode.ITEMS, max);
    }

    /** A kind's heading over its results: clicked, the search looks at that kind alone. */
    private JMenuItem heading(SearchMode kind)
    {
        String name = kind == SearchMode.PLACES ? "Places" : kind == SearchMode.MONSTERS ? "Monsters and NPCs" : "Items";
        JMenuItem heading = new JMenuItem(name + "  ›");
        heading.putClientProperty("heading", Boolean.TRUE);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setToolTipText("Search " + name.toLowerCase(Locale.ROOT) + " only");
        heading.addActionListener(e -> keepFocus(() -> {
            mode = kind;
            updateSearchMode();
            showResults();
        }));
        return heading;
    }

    private List<JMenuItem> placeResults(String query, int max)
    {
        List<JMenuItem> menu = new ArrayList<>();
        SearchIndex index = data == null ? new SearchIndex(Collections.emptyList()) : data.search;
        List<SearchIndex.Hit> hits = index.find(query, MAX_RESULTS * 2, hit -> {
            switch (hit.type)
            {
                case ICON:
                    Poi poi = (Poi) hit.target;
                    return poi.map != null && view.usable(poi);
                case GAME_ICON:
                    return config.showGameIcons();
                case PLACE:
                case MAP:
                    // The game labels some spots with the monsters there ("Abyssal demons"), some maps are a boss's
                    // ("Nex"): those are found as monsters.
                    return !SearchIndex.namesMonsters(hit, world.get());
                default:
                    return true;
            }
        });
        int shown = 0;
        int shownGameIcons = 0;
        for (SearchIndex.Hit hit : hits)
        {
            if (shown >= max || hit.type == SearchIndex.Type.GAME_ICON && ++shownGameIcons > MAX_GAME_ICONS)
            {
                continue;
            }
            shown++;
            JMenuItem item = new JMenuItem(hit.label);
            if (hit.icon != null)
            {
                item.setIcon(new ImageIcon(PoiIcons.image(hit.icon, 14)));
            }
            else if (hit.type == SearchIndex.Type.KIND || hit.type == SearchIndex.Type.KIND_PLACE)
            {
                item.setIcon(new ImageIcon(SearchResults.dotIcon()));
            }
            item.addActionListener(e -> choose(hit));
            menu.add(item);
        }
        return menu;
    }

    private static JMenuItem disabled(String text)
    {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }

    private void choose(SearchIndex.Hit hit)
    {
        if (hit.type != SearchIndex.Type.KIND && hit.type != SearchIndex.Type.KIND_PLACE)
        {
            // A place, map or icon: the last search's results (a monster, an item) go, not stay beside it.
            clearSearch();
        }
        switch (hit.type)
        {
            case PLACE:
                view.focus(((MapData.Place) hit.target).point);
                break;
            case MAP:
                openMap((BaseMap) hit.target);
                break;
            case KIND:
            case KIND_PLACE:
                npcs.showKind((KindIndex.Kind) hit.target, this::placeName);
                if (hit.type == SearchIndex.Type.KIND_PLACE)
                {
                    npcs.lookAtPoint(hit.point);
                }
                break;
            default:
                view.focus((Poi) hit.target);
                break;
        }
    }

    /** The monsters and NPCs the server's lists place, or the items they know, whose names hold what is typed. */
    private List<JMenuItem> worldResults(String typed, boolean items, int max)
    {
        List<JMenuItem> menu = new ArrayList<>();
        Consumer<String> find = items ? npcs::findItem : npcs::findMonster;
        List<String> titles = items ? world.get().itemSuggestions(typed, max) : world.get().npcSuggestions(typed, max);
        for (String title : titles)
        {
            JMenuItem suggestion = new JMenuItem(title);
            suggestion.setIcon(new ImageIcon(items ? SearchResults.itemIcon() : SearchResults.dotIcon()));
            suggestion.addActionListener(e -> {
                search.setText(title);
                typing.stop();
                find.accept(title);
            });
            menu.add(suggestion);
        }
        return menu;
    }

    String placeName(WorldPoint point)
    {
        return placeName(view.maps(), view.labels(), point);
    }

    MapData.Place place(WorldPoint point)
    {
        return place(view.maps(), view.labels(), point);
    }

    static String placeName(BaseMaps baseMaps, List<MapData.Place> labels, WorldPoint point)
    {
        BaseMap map = baseMaps == null ? null : baseMaps.find(point);
        return map != null && map.id != BaseMap.SURFACE ? map.name : MapData.settlement(labels, point);
    }

    /** A town this near names a place outright; up to {@link #FAR_TOWN} away, with a direction. */
    private static final int NEAR_TOWN = 120;
    private static final int FAR_TOWN = 400;
    /** How far a region's label names a place. */
    private static final int REGION_RANGE = 600;

    /**
     * What to call where NPCs stand, with the point that name stands for (so places of one name can say which side
     * they are): a dungeon's map, with its Wilderness level; the Wilderness with its level; the town within reach; the
     * nearest town further off with a direction ("north of Edgeville"); else the nearest region. Null without labels.
     */
    static MapData.Place place(BaseMaps baseMaps, List<MapData.Place> labels, WorldPoint point)
    {
        int level = wildernessLevel(point);
        String wilderness = level > 0 ? ", level " + level : "";
        BaseMap map = baseMaps == null ? null : baseMaps.find(point);
        if (map != null && map.id != BaseMap.SURFACE && map.id != BaseMap.FULL)
        {
            return mapPlace(map, point);
        }
        if (level > 0)
        {
            return new MapData.Place(new WorldPoint(3168, point.getY(), point.getPlane()), "Wilderness" + wilderness,
                null);
        }
        // Of equals, the last the game lists.
        List<MapData.Place> last = Lists.reverse(labels);
        MapData.Place town = MapData.nearest(last, point, "settlement", FAR_TOWN, 0);
        if (town != null)
        {
            return town.point.distanceTo2D(point) <= NEAR_TOWN ? town
                : new MapData.Place(town.point, compass(town.point, point) + " of " + town.name, null);
        }
        // Not a region a world away (Last Man Standing's Wild Varrock for a cave under Rellekka): none.
        return MapData.nearest(last, point, "region", REGION_RANGE, 0);
    }

    /** A dungeon's map as the name of a place in it, with its Wilderness level. */
    static MapData.Place mapPlace(BaseMap map, WorldPoint point)
    {
        int level = wildernessLevel(point);
        return new MapData.Place(new WorldPoint(map.centerX, map.centerY, point.getPlane()),
            map.name + (level > 0 ? ", level " + level : ""), null);
    }

    /** The Wilderness level of a point (level n starts 8(n-1) tiles north of its edge, above or below ground); else 0. */
    static int wildernessLevel(WorldPoint point)
    {
        int x = point.getX();
        int y = point.getY();
        int start = y >= 9920 ? 9920 : 3520;
        return x >= 2944 && x < 3392 && y >= start && y < start + 56 * 8 ? (y - start) / 8 + 1 : 0;
    }

    /** The direction of {@code to} from {@code from}: "north", "south-east", ... */
    static String compass(WorldPoint from, WorldPoint to)
    {
        double angle = Math.toDegrees(Math.atan2(to.getY() - from.getY(), to.getX() - from.getX()));
        String[] names = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
        return names[(int) Math.floorMod(Math.round(angle / 45), 8)];
    }

    private List<BaseMap> allMaps()
    {
        return IntStream.range(0, maps.getItemCount()).mapToObj(maps::getItemAt).collect(Collectors.toList());
    }
}
