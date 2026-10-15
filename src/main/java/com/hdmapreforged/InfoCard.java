package com.hdmapreforged;

import static com.hdmapreforged.PoiType.TELEPORT;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

/** Details of the selected icon (requirements, destinations, links), or the teleports nearest a chosen point. */
final class InfoCard extends Fitted
{
    static final int MAX_LINKS = 60;
    static final Color MET = new Color(110, 200, 110);
    static final Color MISSING = new Color(230, 110, 100);
    static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;

    final MapView map;
    final Supplier<WorldData> world;
    final ItemNames itemNames;
    final HdMapReforgedConfig config;
    Poi poi;
    WorldPoint nearestTo;
    List<Poi> nearest = new ArrayList<>();
    int textWidth = 160;
    IntFunction<JComponent> route;
    /** The route's steps take milliseconds to build: while the card is hidden they wait, built once it shows. */
    boolean routeStale;
    @Setter
    Runnable onRebuilt = () -> { };

    InfoCard(MapView map, Supplier<WorldData> world, ItemNames itemNames, HdMapReforgedConfig config)
    {
        // As JPanel(); a page down keeps 16 pixels of the page before.
        super(new FlowLayout(), 16);
        this.map = map;
        this.world = world;
        this.itemNames = itemNames;
        this.config = config;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        // Shown again (the map opened, the card unfolded): the route's steps that waited are built now.
        addHierarchyListener(e -> {
            if (routeStale && route != null && isShowing())
            {
                routeStale = false;
                refill(routeHolder, route);
            }
        });
        refresh();
    }

    void setTextWidth(int width)
    {
        textWidth = width;
        refresh();
    }

    List<ItemSources.Ware> wares;
    /** The shop a shop icon stands for, for its shopkeeper's picture. */
    WorldData.Shop shop;
    @Setter
    Consumer<String> itemSearch;
    @Setter
    NpcPictures npcPictures = new NpcPictures((id, done) -> done.accept(null));

    Consumer<Tour.Stop> stopAdder;

    void setStopAdder(Consumer<Tour.Stop> stopAdder)
    {
        this.stopAdder = stopAdder;
        refresh();
    }

    Consumer<WorldPoint> router;

    void setRouter(Consumer<WorldPoint> router)
    {
        this.router = router;
        refresh();
    }

    void show(Poi poi)
    {
        this.poi = poi;
        nearestTo = null;
        shop = poi != null && poi.type == PoiType.SHOP ? world.get().shop(poi.location) : null;
        wares = shop == null ? null : world.get().wares(poi.location);
        if (poi != null)
        {
            var ids = new ArrayList<Integer>();
            poi.members().forEach(member -> ids.addAll(Requirements.itemIds(member.needs.items)));
            poi.links().forEach(link -> ids.addAll(Requirements.itemIds(link.needs.items)));
            // The wares' prices.
            if (wares != null)
            {
                wares.forEach(ware -> ids.add(ware.itemId));
            }
            if (!ids.isEmpty())
            {
                itemNames.resolve(ids, () -> {
                    if (this.poi == poi)
                    {
                        refresh();
                    }
                });
            }
        }
        refresh();
    }

    /** A route step passed rebuilds only the route: not the search list beside it, nor details that hide it. */
    void setRoute(IntFunction<JComponent> route, boolean show)
    {
        boolean had = this.route != null;
        this.route = route;
        if (show && route != null)
        {
            poi = null;
            nearestTo = null;
        }
        else if (poi != null || nearestTo != null)
        {
            return;
        }
        else if (had && route != null && routeHolder.getParent() == this && !isShowing())
        {
            // Built once the card shows.
            routeStale = true;
            return;
        }
        else if (had && route != null && refill(routeHolder, route))
        {
            return;
        }
        refresh();
    }

    boolean hasRoute()
    {
        return route != null;
    }

    /** The route again in place of a place or extra shown since (the map opened while one is walked). */
    void showRoute()
    {
        if (route != null)
        {
            setRoute(route, true);
        }
    }

    void setExtra(IntFunction<JComponent> extra)
    {
        this.extra = extra;
        if (extra != null)
        {
            poi = null;
            nearestTo = null;
        }
        refresh();
    }

    /**
     * A background update of {@code old}: taken only while it is still the card's extra, re-rendered only while shown
     * (no place's details open over it), never closing anything.
     */
    boolean updateExtra(IntFunction<JComponent> old, IntFunction<JComponent> now)
    {
        if (extra == null || extra != old)
        {
            return false;
        }
        extra = now;
        if (poi == null && nearestTo == null && !refill(extraHolder, now))
        {
            refresh();
        }
        return true;
    }

    boolean hasExtra()
    {
        return extra != null;
    }

    IntFunction<JComponent> extra;
    /** The extra and the route shown each in a holder of its own, so one can change without the other. */
    final JPanel extraHolder = SearchResults.column();
    final JPanel routeHolder = SearchResults.column();

    /** {@code holder} holding {@code section} alone, at the card's text width. */
    JPanel fill(JPanel holder, IntFunction<JComponent> section)
    {
        holder.removeAll();
        holder.add(section.apply(textWidth));
        return holder;
    }

    /** Only {@code holder} built again, while the card shows it; false when the card must be built whole. */
    boolean refill(JPanel holder, IntFunction<JComponent> section)
    {
        if (holder.getParent() != this || waiting != null)
        {
            return false;
        }
        fill(holder, section);
        revalidate();
        repaint();
        onRebuilt.run();
        return true;
    }

    void showNearest(WorldPoint point, List<Poi> found)
    {
        map.select(null);
        poi = null;
        nearestTo = point;
        nearest = new ArrayList<>(found);
        refresh();
    }

    /** Not while the map animates: building a card with many rows made the map's flight stutter. */
    void refresh()
    {
        if (!map.isAnimating())
        {
            if (waiting != null)
            {
                waiting.stop();
                waiting = null;
            }
            rebuildNow();
            return;
        }
        if (waiting == null)
        {
            // Until then only the name, so the old selection's buttons cannot be used.
            removeAll();
            if (poi != null)
            {
                JLabel title = SearchResults.bold(plain(poi.name));
                title.setForeground(Color.WHITE);
                title.setAlignmentX(Component.LEFT_ALIGNMENT);
                add(title);
            }
            revalidate();
            repaint();
            waiting = new Timer(40, e -> {
                if (!map.isAnimating())
                {
                    waiting.stop();
                    waiting = null;
                    rebuildNow();
                }
            });
            waiting.start();
        }
    }

    Timer waiting;

    void rebuildNow()
    {
        removeAll();
        if (nearestTo != null)
        {
            buildNearest();
        }
        else if (poi == null && (extra != null || route != null))
        {
            if (extra != null)
            {
                add(fill(extraHolder, extra));
            }
            if (extra != null && route != null)
            {
                add(Box.createVerticalStrut(8));
                var line = new JSeparator();
                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                add(line);
                add(Box.createVerticalStrut(4));
            }
            if (route != null)
            {
                routeStale = !isShowing();
                add(routeStale ? routeHolder : fill(routeHolder, route));
            }
        }
        else if (poi == null)
        {
            add(grey("Click an icon for details. Scroll to zoom, drag to move, double-click to zoom in. "
                + "Right-click for the nearest teleports to a spot."));
        }
        else
        {
            buildDetails();
        }
        revalidate();
        repaint();
        onRebuilt.run();
    }

    void buildNearest()
    {
        add(text("Nearest teleports to this spot", Color.WHITE, true));
        add(grey("As the crow flies, from where each one lands. Walking routes may differ."));
        if (nearest.isEmpty())
        {
            add(grey("None on this map."));
        }
        for (var found : nearest)
        {
            JLabel entry = link(found.name + "  (" + found.location.distanceTo2D(nearestTo) + " tiles)", () -> map.focus(found));
            entry.setIcon(new ImageIcon(PoiIcons.image(found.type, 12)));
            add(entry);
        }
    }

    void buildDetails()
    {
        JLabel title = text(poi.name, Color.WHITE, true);
        title.setIcon(new ImageIcon(PoiIcons.image(poi.type, 16)));
        title.setIconTextGap(6);
        add(title);
        String where = poi.type.displayName + (poi.map != null ? " · " + poi.map.name : "")
            + (poi.location.getPlane() > 0 ? " · floor " + poi.location.getPlane() : "");
        add(grey(where));

        var buttons = new ArrayList<JButton>();
        var inside = MapView.mapBelow(poi);
        if (inside != null)
        {
            buttons.add(SearchResults.button(MapView.goInText(inside), () -> map.goIn(inside)));
        }
        var target = poi;
        if (router != null)
        {
            buttons.add(SearchResults.tip(SearchResults.button("Route", () -> router.accept(target.location)),
                "A route from where you are to here"));
        }
        if (stopAdder != null)
        {
            buttons.add(SearchResults.tip(SearchResults.button("+ Stop", () -> stopAdder.accept(Tour.Stop.place(
                target.name, target.location))), "Add to your custom route (right-click the map, Custom routes...)"));
        }
        buttons.add(SearchResults.button("Wiki", () -> LinkBrowser.browse(
            Wiki.searchUrl(poi.wikiQuery != null ? poi.wikiQuery : poi.name))));
        // Slimmer than usual, so more share a row.
        buttons.forEach(button -> button.setMargin(new Insets(2, 6, 2, 6)));
        add(Box.createVerticalStrut(6));
        add(SearchResults.wrapLabels(buttons, 4, textWidth + 70));
        if (extra != null)
        {
            add(Box.createVerticalStrut(4));
            add(link("← Back to the search results", () -> map.select(null)));
        }

        if (poi.note != null)
        {
            add(Box.createVerticalStrut(6));
            add(text(poi.note, new Color(255, 200, 120), false));
        }

        if (!poi.nearby().isEmpty())
        {
            boolean teleports = poi.nearby().stream().allMatch(p -> p.type == TELEPORT);
            heading(teleports ? "Teleports that land here" : "Also here");
            for (var other : poi.nearby())
            {
                for (var member : other.members())
                {
                    addMember(member, member.type == TELEPORT || teleports ? ""
                        : "  (" + member.type.displayName + ")");
                }
            }
        }

        if (poi.type == PoiType.SHOP)
        {
            buildWares();
        }

        var members = poi.members();
        if (members.size() > 1)
        {
            heading("Ways to get here (" + members.size() + ")");
            for (var member : members)
            {
                addMember(member, "");
            }
        }
        var own = Requirements.describe(poi.needs, itemNames);
        if (members.size() <= 1 && !own.isEmpty())
        {
            heading("Requirements");
            addRequirements(own, "");
        }

        var links = poi.links();
        var ownLines = new HashSet<String>();
        own.forEach(line -> ownLines.add(line.text));
        if (!links.isEmpty())
        {
            heading(poi.type.isRandomDestination() ? "Can send you to (" + links.size() + ")"
                : poi.type.isNetwork() ? "Travel to (" + links.size() + ")" : "Leads to");
            var lines = new JCheckBox("Show lines on the map", map.linesShown(poi.type));
            lines.setOpaque(false);
            lines.setFocusable(false);
            lines.setFont(FontManager.getRunescapeSmallFont());
            lines.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            lines.setAlignmentX(Component.LEFT_ALIGNMENT);
            String type = poi.type.displayName.toLowerCase(Locale.ROOT);
            lines.setToolTipText("Draw lines from this " + type + " to where it leads; remembered for every " + type);
            lines.addActionListener(e -> map.setLinesShown(target.type, lines.isSelected()));
            add(lines);
            for (int i = 0; i < links.size() && i < MAX_LINKS; i++)
            {
                var link = links.get(i);
                boolean usable = usable(link.needs);
                if (!usable && config.onlyUsable())
                {
                    continue;
                }
                String label = link.label + (link.map != null && link.map != poi.map && !link.label.contains(link.map.name)
                    ? "  (" + link.map.name + ")" : "");
                JLabel entry = link((usable ? "" : "✗ ") + label, () -> goTo(link));
                if (!usable)
                {
                    entry.setToolTipText("You do not meet a requirement for this trip");
                }
                add(entry);
                // Only what this trip needs beyond the stop's own requirements, listed above.
                var tripNeeds = Requirements.describe(link.needs, itemNames);
                tripNeeds.removeIf(line -> ownLines.contains(line.text));
                if (!tripNeeds.isEmpty())
                {
                    addRequirements(tripNeeds, "    ");
                }
            }
        }

        var group = groupOf(poi);
        if (!group.isEmpty())
        {
            String kind = poi.group.toLowerCase(Locale.ROOT).endsWith("teleports") ? poi.group : poi.group + " teleports";
            heading("Other " + kind + " (" + group.size() + ")");
            for (int i = 0; i < group.size() && i < MAX_LINKS; i++)
            {
                var other = group.get(i);
                add(link(shortName(other), () -> map.focus(other)));
            }
        }
    }

    boolean usable(Needs needs)
    {
        return map.unlocks() == null || map.unlocks().usable(needs);
    }

    void addMember(Poi member, String kind)
    {
        boolean usable = usable(member.needs);
        if (!usable && config.onlyUsable())
        {
            return;
        }
        add(text((usable ? "" : "✗ ") + member.name + kind, usable ? Color.WHITE : MISSING, false));
        var needs = Requirements.describe(member.needs, itemNames);
        if (!needs.isEmpty())
        {
            addRequirements(needs, "    ");
        }
    }

    void buildWares()
    {
        if (wares == null || wares.isEmpty())
        {
            return;
        }
        if (shop != null && shop.npc >= 0)
        {
            JLabel keeper = SearchResults.pictureIcon(NpcPictures.LARGE);
            npcPictures.show(shop.npc, keeper);
            add(SearchResults.pictureRow(keeper, grey(shop.name)));
        }
        heading("Sells (" + wares.size() + ")");
        add(SearchResults.choice(new String[][]{{"shop", "Shop order"}, {"stock", "Stock"}, {"name", "Name"},
            {"price", "Price"}}, wareSort, sort -> {
                wareSort = sort;
                refresh();
            }, textWidth));
        var sorted = new ArrayList<ItemSources.Ware>(wares);
        sorted.sort(ItemSources.wareOrder(wareSort, itemNames::price));
        for (ItemSources.Ware ware : sorted)
        {
            JLabel icon = SearchResults.pictureIcon(SearchResults.PICTURE);
            itemNames.picture(ware.itemId, icon);
            // The name, like a drop's in the search panel, looks the item up in the plugin: where else it comes from.
            Runnable open = itemSearch != null ? () -> itemSearch.accept(ware.item)
                : () -> LinkBrowser.browse(Wiki.pageUrl(ware.item));
            JComponent name = SearchResults.tip(SearchResults.bold(link(ware.item, open)),
                itemSearch != null ? "Where else to get " + ware.item : "Open " + ware.item + " on the wiki");
            int price = itemNames.price(ware.itemId);
            JPanel line = SearchResults.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            line.add(SearchResults.tip(grey(SearchResults.dotted(ItemSources.stockText(ware.stock),
                price >= 0 ? "GE " + SearchResults.gp(price) : null) + " · "),
                "How many the shop keeps when no one has bought any, and the Grand Exchange price"));
            line.add(SearchResults.tip(link("Wiki", () -> LinkBrowser.browse(Wiki.pageUrl(ware.item))),
                "Open " + ware.item + " on the wiki"));
            add(Box.createVerticalStrut(3));
            add(SearchResults.clickableRow(SearchResults.pictureRow(icon, name, line), open));
        }
    }

    /** How a shop's wares are ordered; kept from one shop to the next. */
    String wareSort = "shop";

    void addRequirements(List<Requirements.Line> lines, String indent)
    {
        var unlocks = map.unlocks();
        for (var line : lines)
        {
            Boolean met = unlocks == null ? null : unlocks.met(line);
            String mark = met == null ? "• " : met ? "✓ " : "✗ ";
            Color color = met == null ? Color.WHITE : met ? MET : MISSING;
            add(text(indent + mark + line.text, color, false));
        }
    }

    List<Poi> groupOf(Poi poi)
    {
        var group = new ArrayList<Poi>();
        if (poi.type != TELEPORT || poi.group == null)
        {
            return group;
        }
        for (var other : map.pois())
        {
            var member = other.memberOf(poi.group);
            if (other != poi && other.type == TELEPORT && member != null && map.usable(member.needs))
            {
                group.add(other);
            }
        }
        return group;
    }

    String shortName(Poi poi)
    {
        var member = poi.memberOf(this.poi.group);
        String name = member != null ? member.name : poi.name;
        int colon = name.indexOf(':');
        return colon > 0 ? name.substring(colon + 1).trim() : name;
    }

    void goTo(Poi.Link link)
    {
        for (var other : map.pois())
        {
            if (other != poi && other.type == poi.type && other.location.getPlane() == link.point.getPlane()
                && other.location.distanceTo2D(link.point) <= 12)
            {
                map.focus(other);
                return;
            }
        }
        map.focus(link.point);
    }

    /** A heading as the search panel's. */
    void heading(String text)
    {
        add(Box.createVerticalStrut(8));
        add(SearchResults.heading(text, textWidth));
        add(Box.createVerticalStrut(2));
    }

    /** Names as they are (places, party members): a leading {@code <html>} must not render as HTML (remote images). */
    static JLabel plain(String text)
    {
        JLabel label = new JLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setText(text);
        return label;
    }

    JLabel grey(String text)
    {
        return text(text, SearchResults.SOFT, false);
    }

    /** As the search panel's, without the space between lines of a list. */
    JLabel text(String text, Color color, boolean bold)
    {
        return SearchResults.text(text, color, bold ? Font.BOLD : Font.PLAIN, bold ? 1 : 0, textWidth);
    }

    JLabel link(String text, Runnable action)
    {
        return SearchResults.clickable(text(text, LINK, false), action, true);
    }

    static String escape(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
