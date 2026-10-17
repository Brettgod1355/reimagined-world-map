package com.hdmapreforged;

import static com.hdmapreforged.HdMapReforgedConfig.*;
import static com.hdmapreforged.HdMapPartyGear.*;

import java.awt.*;
import java.awt.Point;
import java.awt.event.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.party.*;
import net.runelite.client.party.events.*;
import net.runelite.client.party.messages.*;
import net.runelite.client.plugins.party.messages.*;
import net.runelite.client.util.*;

/**
 * Party members on the map through RuneLite's party service. Nothing is sent outside a party, and a party is never
 * joined without a user click: "Rejoin party" rejoins the remembered last one.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class PartyMap implements MapPanel.Friends, FriendsWidget.Images
{
    static final String LAST_KEY = "partyLast";
    static final List<Class<? extends WebsocketMessage>> MESSAGES =
        List.of(HdMapPartyLocation.class, HdMapPartyGear.class, HdMapPartyDrop.class);

    // The constructor takes these in this order.
    final Client client;
    final PartyService party;
    final WSClient wsClient;
    final EventBus eventBus;
    final ConfigManager configManager;
    final HdMapReforgedConfig config;
    final ItemManager itemManager;
    final SkillIconManager skillIcons;
    final ClientThread clientThread;
    final SpriteManager spriteManager;

    final PartyMapMembers members = new PartyMapMembers();
    final PartyMapRules.Throttle throttle = new PartyMapRules.Throttle();
    // Initializers may read later or constructor-set fields only through "this" or "PartyMap.this".
    final PartyMapOverlay overlay = new PartyMapOverlay(this::markers, () -> this.myWorld);

    {
        overlay.selected = id -> this.widgets.values().stream().anyMatch(w -> w.selected() == id);
        overlay.hovered = id -> id == this.hoveredMember;
        overlay.setLoot(id -> members.loot(id, System.currentTimeMillis()), this::item, this::repaint);
    }

    /** Swing thread only; each view's value undoes what {@link #start} added to it. */
    final Map<MapView, Runnable> views = new LinkedHashMap<>();
    final Map<MapView, FriendsWidget> widgets = new LinkedHashMap<>();
    volatile int myWorld;
    /** The party this plugin joined for the user, or null. */
    volatile String joinedCode;
    volatile long lastChange = Long.MIN_VALUE / 2;
    Timer idleCheck;
    volatile boolean sentOnline;
    volatile Long loggedOutSince;

    volatile boolean gearChanged = true;
    int gearSentTick = Integer.MIN_VALUE / 2;
    /** The members as last read without a clash with the websocket thread. */
    volatile List<FriendsWidget.Member> lastGroup = Collections.emptyList();
    final Map<Long, BufferedImage> itemImages = new ConcurrentHashMap<>();

    void start(MapView... maps)
    {
        MESSAGES.forEach(wsClient::registerMessage);
        eventBus.register(this);
        for (int id : Orbs.SPRITES)
        {
            spriteManager.getSpriteAsync(id, 0, image -> {
                sprites.put(id, image);
                repaint();
            });
        }
        loggedOutSince = client.getGameState() == GameState.LOGGED_IN ? null : System.currentTimeMillis();
        SwingUtilities.invokeLater(() -> {
            for (MapView view : maps)
            {
                MapView.MenuContributor menu = (popup, point) -> contribute(popup, view);
                view.addOverlay(overlay);
                view.menuContributors.add(menu);
                FriendsWidget widget = new FriendsWidget(this::markers, this::groupMembers, party::isInParty,
                    () -> myWorld, config::partyFriendsTab, row -> follow(view, row.id), view::repaint);
                widget.setDrops(id -> members.drops(id, System.currentTimeMillis()));
                widget.canJoin = () -> !party.isInParty() && lastParty() != null;
                widget.join = () -> join(view);
                MapView.ClickCatcher catcher = (at, projection) -> clickedMember(view, widget, at, projection);
                view.clickCatchers.add(catcher);
                MouseAdapter pointing = new MouseAdapter()
                {
                    @Override
                    public void mouseMoved(MouseEvent e)
                    {
                        long now = memberAt(e.getPoint(), view.projection());
                        if (now != hoveredMember)
                        {
                            hoveredMember = now;
                            view.repaint();
                        }
                    }

                    @Override
                    public void mouseExited(MouseEvent e)
                    {
                        if (hoveredMember >= 0)
                        {
                            hoveredMember = -1;
                            view.repaint();
                        }
                    }
                };
                view.addMouseMotionListener(pointing);
                view.addMouseListener(pointing);
                widget.gear = members::gear;
                widget.images = this;
                widgets.put(view, widget);
                view.addWidget(widget);
                views.put(view, () -> {
                    view.removeOverlay(overlay);
                    view.menuContributors.remove(menu);
                    view.clickCatchers.remove(catcher);
                    view.removeMouseMotionListener(pointing);
                    view.removeMouseListener(pointing);
                    view.removeWidget(widget);
                    widget.ticker.stop();
                });
            }
            idleCheck = new Timer(30_000, e -> checkIdle());
            idleCheck.start();
        });
    }

    void stop()
    {
        sendOffline();
        eventBus.unregister(this);
        MESSAGES.forEach(wsClient::unregisterMessage);
        members.clear();
        throttle.reset();
        itemImages.clear();
        sprites.clear();
        SwingUtilities.invokeLater(() -> {
            if (idleCheck != null)
            {
                idleCheck.stop();
                idleCheck = null;
            }
            views.values().forEach(Runnable::run);
            views.clear();
            widgets.clear();
            overlay.ticker.stop();
            if (PartyMapRules.leaveOnShutdown(joinedCode, party.getPartyPassphrase()))
            {
                party.changeParty(null);
            }
            joinedCode = null;
        });
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        myWorld = client.getWorld();
    }

    volatile long hoveredMember = -1;

    boolean clickedMember(MapView view, FriendsWidget widget, Point at, MapView.Projection projection)
    {
        long best = memberAt(at, projection);
        if (best < 0)
        {
            return false;
        }
        widget.show(best);
        follow(view, best);
        repaint();
        return true;
    }

    long memberAt(Point at, MapView.Projection projection)
    {
        long best = -1;
        double bestDistance = 14;
        for (PartyMapMembers.Marker marker : markers())
        {
            // Where the map draws them, which can differ from the game.
            WorldPoint shown = projection.shown(marker.point);
            if (!projection.shows(marker.point))
            {
                continue;
            }
            double d = at.distance(projection.screenX(shown.getX() + 0.5), projection.screenY(shown.getY() + 0.5));
            if (d < bestDistance)
            {
                bestDistance = d;
                best = marker.id;
            }
        }
        return best;
    }

    final Map<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
    /** The sidebar's and the map's pictures of what members share. */
    @Override
    public BufferedImage skill(int ordinal)
    {
        Skill[] skills = Skill.values();
        return ordinal < 0 || ordinal >= Math.min(SKILLS, skills.length) ? null
            : skillIcons.getSkillImage(skills[ordinal], true);
    }

    @Override
    public BufferedImage sprite(int id)
    {
        return sprites.get(id);
    }

    /** The sidebar's party list. */
    @Override
    public boolean inGroup()
    {
        return party.isInParty();
    }

    @Override
    public List<FriendsWidget.Row> rows()
    {
        return FriendsWidget.rows(markers(), groupMembers());
    }

    @Override
    public int world()
    {
        return myWorld;
    }

    @Override
    public PartyMapMembers.Gear gear(long id)
    {
        return members.gear(id);
    }

    @Override
    public FriendsWidget.Images images()
    {
        return this;
    }

    @Override
    public boolean canRejoin()
    {
        return lastParty() != null;
    }

    @Override
    public void follow(long id)
    {
        views.keySet().forEach(view -> follow(view, id));
    }

    /** Follows a member clicked: the map keeps them in view as they move. Swing thread. */
    void follow(MapView view, long id)
    {
        Supplier<WorldPoint> at = () -> markers().stream().filter(m -> m.id == id).map(m -> m.point).findFirst()
            .orElse(null);
        if (at.get() != null)
        {
            view.follow(at);
        }
    }

    List<FriendsWidget.Member> groupMembers()
    {
        var list = new ArrayList<FriendsWidget.Member>();
        if (!party.isInParty())
        {
            return list;
        }
        List<PartyMember> all;
        try
        {
            // The websocket thread changes this list while we read it on the Swing thread.
            all = new ArrayList<>(party.getMembers());
        }
        catch (ConcurrentModificationException | ArrayIndexOutOfBoundsException e)
        {
            return new ArrayList<>(lastGroup);
        }
        for (PartyMember member : all)
        {
            if (member != null && !isLocal(member.getMemberId()))
            {
                // RuneLite names a member "<unknown>" until they log in and send their name.
                String name = member.getDisplayName();
                list.add(new FriendsWidget.Member(member.getMemberId(),
                    name == null || name.startsWith("<") ? null : PartyMapMembers.clean(name)));
            }
        }
        lastGroup = List.copyOf(list);
        return list;
    }

    List<PartyMapMembers.Marker> markers()
    {
        return config.partyShowMembers() ? members.markers(System.currentTimeMillis()) : Collections.emptyList();
    }

    void tick(WorldPoint location, boolean house, int tick)
    {
        if (!members.isEmpty() && tick % 5 == 0)
        {
            // Markers fade over minutes: every few seconds is enough (animations have their own ticker).
            repaint();
        }
        // Run and special attack energy alone are no reason to send.
        if (gearChanged && party.isInParty() && config.partyShareGear()
            && tick - gearSentTick >= PartyMapRules.GEAR_TICKS)
        {
            gearChanged = false;
            gearSentTick = tick;
            party.send(gear());
        }
        if (party.isInParty() && config.partyShareLocation() && throttle.shouldSend(location, tick))
        {
            party.send(new HdMapPartyLocation(location, client.getWorld(), localName(), house));
            sentOnline = true;
        }
    }

    String localName()
    {
        Player player = client.getLocalPlayer();
        return player == null || player.getName() == null ? null : Text.removeTags(player.getName());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGGED_IN)
        {
            loggedOutSince = null;
        }
        else if (state == GameState.LOGIN_SCREEN)
        {
            if (loggedOutSince == null)
            {
                loggedOutSince = System.currentTimeMillis();
            }
            sendOffline();
            throttle.reset();
            myWorld = 0;
        }
        else if (state == GameState.HOPPING)
        {
            // The same tile on another world: sent at once, not only with the next heartbeat.
            throttle.force();
        }
    }

    void sendOffline()
    {
        if (sentOnline && party.isInParty())
        {
            party.send(HdMapPartyLocation.offline(null));
        }
        sentOnline = false;
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!GROUP.equals(event.getGroup()) || !event.getKey().startsWith("party"))
        {
            return;
        }
        if ("partyShareLocation".equals(event.getKey()) && !config.partyShareLocation() && party.isInParty()
            && client.getGameState() == GameState.LOGGED_IN)
        {
            party.send(HdMapPartyLocation.offline(null));
            sentOnline = false;
        }
        throttle.force();
        repaint();
    }

    // Party events arrive on the websocket thread.

    @Subscribe
    public void onHdMapPartyLocation(HdMapPartyLocation message)
    {
        if (isLocal(message.getMemberId()))
        {
            return;
        }
        members.update(message.getMemberId(), message.point(), message.world(), message.name(),
            message.house(), System.currentTimeMillis());
        memberDetails(message.getMemberId());
        repaint();
    }

    /** RuneLite's Party plugin's messages, for members without this plugin. */
    @Subscribe
    public void onLocationUpdate(LocationUpdate message)
    {
        if (isLocal(message.getMemberId()))
        {
            return;
        }
        WorldPoint point = message.getWorldPoint();
        if (!HdMapPartyLocation.plausible(point))
        {
            return;
        }
        if (members.updateFromCore(message.getMemberId(), point, System.currentTimeMillis()))
        {
            memberDetails(message.getMemberId());
            repaint();
        }
    }

    @Subscribe
    public void onUserJoin(UserJoin event)
    {
        throttle.force();
        gearChanged = true;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();
        if (id == InventoryID.INV || id == InventoryID.WORN)
        {
            gearChanged = true;
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        gearChanged = true;
    }

    @Subscribe
    public void onHdMapPartyGear(HdMapPartyGear message)
    {
        if (isLocal(message.getMemberId()))
        {
            return;
        }
        members.gear(message.getMemberId(), new PartyMapMembers.Gear(message, System.currentTimeMillis()));
        repaint();
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        shareDrops(event.getItems());
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event)
    {
        shareDrops(event.getItems());
    }

    void shareDrops(Collection<ItemStack> items)
    {
        // Untradeables go only with drop sharing on: turned off, nothing is shared.
        boolean untradeables = config.partyShareUntradeables();
        if (!party.isInParty() || !config.partyShareDrops())
        {
            return;
        }
        var valuable = new ArrayList<HdMapPartyDrop>();
        for (ItemStack stack : items)
        {
            long value = itemManager.getItemPriceWithSource(stack.getId(), false) * stack.getQuantity();
            // A lower value saved before the setting had its minimum still reads back as it was.
            if (value >= Math.max(MIN_DROP_VALUE, config.partyDropValue())
                || untradeables && !tradeable(stack.getId()))
            {
                valuable.add(new HdMapPartyDrop(stack.getId(), stack.getQuantity(), value));
            }
        }
        // Only the most valuable few; the map shows no more.
        valuable.sort(Comparator.comparingLong((HdMapPartyDrop d) -> -d.value()));
        for (HdMapPartyDrop drop : valuable.subList(0, Math.min(valuable.size(), PartyMapRules.MAX_DROPS_PER_LOOT)))
        {
            party.send(drop);
        }
    }

    boolean tradeable(int item)
    {
        return itemManager.getItemComposition(itemManager.canonicalize(item)).isTradeable();
    }

    @Subscribe
    public void onHdMapPartyDrop(HdMapPartyDrop message)
    {
        int item = message.item();
        if (isLocal(message.getMemberId()) || item < 0)
        {
            return;
        }
        // Only items the game has, valued by our own prices.
        clientThread.invoke(() -> {
            if (item < client.getItemCount())
            {
                long value = itemManager.getItemPriceWithSource(item, false) * message.quantity();
                // An untradeable one shows by name, as it has no price.
                String name = tradeable(item) ? null : itemManager.getItemComposition(item).getName();
                members.loot(message.getMemberId(), new PartyMapMembers.Loot(item, message.quantity(), value,
                    System.currentTimeMillis(), name));
                repaint();
            }
        });
    }

    HdMapPartyGear gear()
    {
        int[] quantities = new int[INVENTORY];
        int[] inventory = items(InventoryID.INV, quantities);
        int[] equipment = items(InventoryID.WORN, new int[EQUIPMENT]);
        int[] levels = new int[SKILLS];
        int[] boosted = new int[SKILLS];
        int[] experience = new int[SKILLS];
        Skill[] skills = Skill.values();
        for (int k = 0; k < Math.min(skills.length, levels.length); k++)
        {
            levels[k] = client.getRealSkillLevel(skills[k]);
            boosted[k] = client.getBoostedSkillLevel(skills[k]);
            experience[k] = client.getSkillExperience(skills[k]);
        }
        return new HdMapPartyGear(inventory, quantities, equipment, levels, boosted, experience,
            client.getEnergy() / 100, client.getVarpValue(VarPlayerID.SA_ENERGY) / 10);
    }

    /** Item ids (-1 where empty) and quantities of a container, as many as {@code quantities} holds. */
    int[] items(int container, int[] quantities)
    {
        int[] ids = new int[quantities.length];
        Arrays.fill(ids, -1);
        ItemContainer items = client.getItemContainer(container);
        Item[] all = items == null ? new Item[0] : items.getItems();
        for (int k = 0; k < Math.min(all.length, ids.length); k++)
        {
            ids[k] = all[k].getId();
            quantities[k] = all[k].getQuantity();
        }
        return ids;
    }

    @Override
    public BufferedImage item(int id, int quantity)
    {
        long key = (long) id << 32 | quantity;
        if (itemImages.size() > 512 && !itemImages.containsKey(key))
        {
            itemImages.clear();
        }
        return itemImages.computeIfAbsent(key, k -> {
            AsyncBufferedImage image = itemManager.getImage(id, quantity, quantity > 1);
            image.onLoaded(this::repaint);
            return image;
        });
    }

    @Subscribe
    public void onUserSync(UserSync event)
    {
        throttle.force();
    }

    @Subscribe
    public void onUserPart(UserPart event)
    {
        members.remove(event.getMemberId());
        repaint();
    }

    @Subscribe
    public void onPartyChanged(PartyChanged event)
    {
        if (event.getPartyId() != null && event.getPassphrase() != null && !event.getPassphrase().isEmpty())
        {
            // Whoever joined it (this plugin or RuneLite's Party panel).
            configManager.setConfiguration(GROUP, LAST_KEY, event.getPassphrase());
        }
        String ours = joinedCode;
        if (ours != null && !ours.equals(event.getPassphrase()))
        {
            // A party joined by hand is the user's own and never left for them.
            joinedCode = null;
        }
        members.clear();
        throttle.reset();
        throttle.force();
        repaint();
    }

    @Subscribe
    public void onPartyMemberAvatar(PartyMemberAvatar event)
    {
        members.avatar(event.getMemberId(), event.getImage());
        repaint();
    }

    boolean isLocal(long memberId)
    {
        PartyMember local = party.getLocalMember();
        return local != null && local.getMemberId() == memberId;
    }

    void memberDetails(long memberId)
    {
        try
        {
            PartyMember member = party.getMemberById(memberId);
            if (member != null)
            {
                members.name(memberId, member.getDisplayName());
                BufferedImage avatar = member.getAvatar();
                if (avatar != null)
                {
                    members.avatar(memberId, avatar);
                }
            }
        }
        catch (ConcurrentModificationException e)
        {
            // The next message fills this in.
        }
    }

    void repaint()
    {
        SwingUtilities.invokeLater(() -> {
            for (MapView view : views.keySet())
            {
                view.followMember();
                view.repaint();
            }
        });
    }

    void contribute(JPopupMenu popup, MapView view)
    {
        JMenu friends = new JMenu("Party");
        String current = party.getPartyPassphrase();
        for (PartyMapMembers.Marker marker : markers())
        {
            String name = marker.name != null ? marker.name : "party member";
            friends.add("Show " + name + (marker.stale() ? " (" + PartyMapOverlay.ago(marker.ageMillis) + ")" : ""))
                .addActionListener(a -> view.focus(marker.point));
        }
        if (friends.getMenuComponentCount() > 0)
        {
            friends.addSeparator();
        }
        if (current != null)
        {
            friends.add("Leave party").addActionListener(a -> leave());
        }
        else if (lastParty() != null)
        {
            friends.add("Rejoin party").addActionListener(a -> join(view));
        }
        if (friends.getMenuComponentCount() == 0)
        {
            friends.add("Join a party in RuneLite's Party panel").setEnabled(false);
        }
        popup.addSeparator();
        popup.add(friends);
    }

    String lastParty()
    {
        String last = configManager.getConfiguration(GROUP, LAST_KEY);
        return last == null || last.trim().isEmpty() ? null : last.trim();
    }

    @Override
    public void join(Component view)
    {
        String code = lastParty();
        if (code == null)
        {
            return;
        }
        long now = System.currentTimeMillis();
        String current = party.getPartyPassphrase();
        switch (PartyMapRules.join(code, client.getGameState() == GameState.LOGGED_IN, current, lastChange, now))
        {
            case ALREADY_IN:
            case INVALID_CODE:
                return;
            case NOT_LOGGED_IN:
                tell(view, "Log in to the game first, then rejoin the party.");
                return;
            case TOO_SOON:
                tell(view, "You just changed party. Try again in a few seconds.");
                return;
            case CONFIRM_LEAVE_OTHER:
                if (JOptionPane.showConfirmDialog(view, "You are in another party. Leave it and rejoin your last party?",
                    "Reimagined World Map", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                {
                    return;
                }
                break;
            default:
                break;
        }
        lastChange = now;
        joinedCode = code;
        party.changeParty(code);
    }

    static void tell(Component to, String message)
    {
        JOptionPane.showMessageDialog(to, message, "Reimagined World Map", JOptionPane.INFORMATION_MESSAGE);
    }

    void leave()
    {
        lastChange = System.currentTimeMillis();
        joinedCode = null;
        party.changeParty(null);
    }

    /** Leaves a party we rejoined after long logged out, as RuneLite's Party plugin does. */
    void checkIdle()
    {
        if (PartyMapRules.leaveIdle(joinedCode, party.getPartyPassphrase(), loggedOutSince, System.currentTimeMillis()))
        {
            log.debug("Leaving the party we rejoined after a long time logged out");
            leave();
        }
    }
}
