package com.hdmapreforged;

import static com.hdmapreforged.route.Tiles.*;
import static java.util.Locale.ROOT;
import static com.hdmapreforged.PoiType.*;
import static com.hdmapreforged.RouteFeature.pack;

import com.hdmapreforged.route.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/** The jumps a route may take for this player. Thread-safe: requests come from Swing and the extras thread. */
@RequiredArgsConstructor
final class RouteSource
{
    static final int SNAP = 5;
    static final int DOCK_REACH = 18;
    static final int PORT_RADIUS = 24;
    /** How far from its dock a port's boat lies, at most (Port Piscarilius: 34 tiles). */
    static final int BOAT_REACH = 40;
    static final Pattern SAILING = Pattern.compile("(\\d+)\\s+Sailing", Pattern.CASE_INSENSITIVE);

    @RequiredArgsConstructor
    static final class Options
    {
        final boolean teleports;
        @With
        final boolean sailing;
        /** Where shipwrights stand (not changed once given). */
        final int[] shipwrights;
        final boolean ignoreLevels;
        final boolean ignoreItems;
        final Set<String> avoid;
        final Saving saving;
        /** The way anyone could go, shown when the player's way does not arrive (not the "ignore" settings). */
        final boolean fallback;
        /** Stay out of the Wilderness unless the route starts or ends there. */
        final boolean avoidWilderness;
        /** Sail also to places on land that can be walked to; else only from or to the sea, or where walking does not get. */
        @With
        final boolean sailToLand;
    }

    /** Tiles a kind of trip must save to be taken (a teleport for a few tiles wastes runes); search weight only. */
    @RequiredArgsConstructor
    static final class Saving
    {
        static final Saving NONE = new Saving(0, 0, 0, 0, 0, 0);

        final int teleport;
        final int shortcut;
        final int transport;
        final int canoe;
        final int carpet;
        final int ship;

        int tiles(PoiType type)
        {
            switch (type)
            {
                case AGILITY_SHORTCUT:
                    return shortcut;
                case CANOE:
                    return canoe;
                case CARPET:
                    return carpet;
                case BOAT:
                case CHARTER:
                    return ship;
                case PORTAL:
                case LEVER:
                    return 0;
                default:
                    return transport;
            }
        }
    }

    /** How near a dock a shipwright, or a port where one's boat may lie, must be to count as there. */
    static final int SHIPWRIGHT_REACH = 40;

    final CollisionMap map;
    final SeaMap sea;
    /** The planner's passages that are Agility shortcuts ({@link Pathfinder#shortcutPassages()}). */
    final List<ShortcutPassage> shortcuts;
    /**
     * {@link Pathfinder#pocket}: a jump's end snaps to open ground, not a shut-in tile beside it, from which no route goes
     * on (Tal Teklan's quetzal, several fairy rings).
     */
    final IntPredicate pocket;
    /** The game's item names, for what a step lacks ("3200 × Coins"); null: "items you do not have". */
    volatile IntFunction<String> itemNames;

    void itemNames(IntFunction<String> names)
    {
        itemNames = names;
    }
    /** Snapped ends by spot (and the spot to keep away from): the map does not change, and requests snap thousands. */
    final Map<Long, Integer> snaps = new ConcurrentHashMap<>();

    RouteRequest request(int start, int target, List<Poi> pois, Unlocks unlocks, PlayerState state, Options options,
        int nodeLimit)
    {
        if (options.sailing && !options.sailToLand && !isSea(target) && !(start >= 0 && isSea(start)))
        {
            // A place on land: walk; the boat only when walking does not get there.
            var walking = request(start, target, pois, unlocks, state, options.withSailing(false).withSailToLand(false),
                nodeLimit);
            var sailing = request(start, target, pois, unlocks, state, options.withSailing(true).withSailToLand(true),
                nodeLimit);
            return walking == null || sailing == null ? sailing : new RouteRequest(walking.start, walking.target,
                walking.startEdges, walking.edges, walking.sailingLevel, walking.running, walking.nodeLimit,
                walking.avoidWilderness, sailing, null);
        }
        if (start >= 0 && HousePlan.contains(start))
        {
            // On the plan of one's house: in it, also before the player's state (read every few ticks) says so.
            state = state.inOwnHouse(true);
        }
        Call call = new Call(options, unlocks, state, itemNames);
        if (options.ignoreLevels)
        {
            unlocks = null;
            state = state.with(state.items, 99, true);
        }
        if (options.ignoreItems)
        {
            state = state.with(ItemSnapshot.EVERYTHING, state.sailingLevel, state.sailing);
        }
        var startEdges = new ArrayList<Edge>();
        var edges = new ArrayList<Edge>();
        var teleportsByName = new HashMap<String, Poi>();
        var all = Poi.flatten(pois);
        call.notGrown = all.stream().filter(this::notPlanted).map(poi -> poi.location).collect(Collectors.toSet());
        for (var poi : all)
        {
            if (poi.type == TELEPORT)
            {
                for (var member : poi.members())
                {
                    teleportsByName.putIfAbsent(member.name.toLowerCase(ROOT), member);
                    if (options.teleports)
                    {
                        teleport(call, member, unlocks, state, startEdges, edges);
                    }
                }
            }
            else if (poi.type == MOORING)
            {
                if (options.sailing)
                {
                    mooring(call, poi, unlocks, state, options, start >= 0 && isSea(start), edges);
                }
            }
            else if (isTransport(poi.type))
            {
                transport(call, poi, unlocks, state, edges);
            }
        }
        gates(call, unlocks, state, edges);
        shortcuts(call, unlocks, state, edges);
        if (options.sailing && state.sailing)
        {
            seaPassages(call, unlocks, state, edges);
        }
        // In an area whose way out leads back to where one came from (the Rune essence mine, the Shipyard): its exits,
        // there, when the player was seen coming in.
        var back = start >= 0 ? ReturnAreas.at(start) : null;
        if (back != null && state.returnExit >= 0)
        {
            for (int exit : back.exits)
            {
                int from = snap(RouteFeature.worldPoint(exit));
                if (from >= 0)
                {
                    edges.add(new Edge(from, state.returnExit, Edge.Kind.ENTRANCE, back.exitName, null, 6));
                }
            }
        }
        if (options.sailing && options.teleports)
        {
            teleportToBoat(unlocks, state, startEdges);
        }
        if (state.inHouse || options.teleports)
        {
            house(call, pois, unlocks, state, teleportsByName, startEdges, edges);
        }
        if (!call.avoiding.isEmpty())
        {
            // Also the house's ways (its jewellery box, portals), by name or kind as the route's steps show them.
            Predicate<Edge> avoided = e -> call.avoids(e.name, e.category);
            startEdges.removeIf(avoided);
            edges.removeIf(avoided);
        }
        if (state.inHouse)
        {
            // In the house its own portal, box or amulet goes before a spell or item, unless that is over 10 tiles quicker.
            startEdges.replaceAll(e -> e.kind == Edge.Kind.TELEPORT ? e.weighed(HOUSE_FIRST) : e);
        }
        if (start < 0 && startEdges.isEmpty())
        {
            return null;
        }
        int level = options.sailing && state.sailing ? state.sailingLevel : -1;
        if (start >= 0 && isSea(start) && level < 0)
        {
            // On a boat without the Sailing data: still let the route sail home.
            level = Math.max(1, state.sailingLevel);
        }
        return new RouteRequest(start, target, startEdges, edges, level, state.running, nodeLimit, options.avoidWilderness);
    }

    /** Half ticks (tiles run) a spell or item in the house must be quicker than its own ways. */
    static final int HOUSE_FIRST = 10;

    static final class Call
    {
        final Set<String> avoiding;
        final Saving saving;
        final Unlocks realUnlocks;
        final Unlocks missingLevels;
        final ItemSnapshot missingItems;
        /** With the bank's items too: what is carried without them (to say what to take out); else null. */
        final ItemSnapshot carried;
        /** What may be used: carried, and with the bank's items too. */
        final ItemSnapshot items;
        final int realSailing;
        final boolean fallback;
        final long minute = System.currentTimeMillis() / 60_000;

        final IntFunction<String> itemNames;
        /** The stops of planted spirit trees not grown for this player: not travelled to. */
        Set<WorldPoint> notGrown = Collections.emptySet();

        Call(Options options, Unlocks unlocks, PlayerState state, IntFunction<String> itemNames)
        {
            this.itemNames = itemNames;
            avoiding = options.avoid;
            saving = options.saving;
            realUnlocks = unlocks;
            missingLevels = options.ignoreLevels ? unlocks : null;
            missingItems = options.ignoreItems ? state.items : null;
            carried = state.carried;
            items = state.items;
            realSailing = !options.ignoreLevels ? 99 : state.sailing ? state.sailingLevel : 0;
            fallback = options.fallback;
        }

        /** Whether the route leaves out a way by its name or by its kind; either may be null. */
        boolean avoids(String name, String category)
        {
            return name != null && avoiding.contains(name.toLowerCase(ROOT))
                || category != null && avoiding.contains(typeKey(category));
        }
    }

    @RequiredArgsConstructor
    static final class Gate
    {
        final WorldPoint from;
        final WorldPoint to;
        final String name;
        final Needs needs;
    }

    /** A way between two seas that sailing alone does not join (sea_passages.tsv): a sea cave. */
    @RequiredArgsConstructor
    static final class SeaPassage
    {
        final int fromX;
        final int fromY;
        final int toX;
        final int toY;
        final String name;
        final Needs needs;
        final String note;
    }

    /** Read once per route.zip, so the unlocks' per-requirement cache hits. */
    static final RouteFiles.Table<List<Gate>> GATES = new RouteFiles.Table<>(RouteSource::readGates);
    static final RouteFiles.Table<List<SeaPassage>> SEA_PASSAGES =
        new RouteFiles.Table<>(RouteSource::readSeaPassages);
    /** The needs of the bundled gates and shortcuts, whose quests and varbits the requirements read must watch too. */
    static List<Needs> fixedNeeds()
    {
        var needs = new ArrayList<Needs>();
        GATES.get().forEach(gate -> needs.add(gate.needs));
        SEA_PASSAGES.get().forEach(passage -> needs.add(passage.needs));
        for (var way : ShortcutPassage.allWays())
        {
            needs.add(new Needs(way[0], way[1], way[2], way[3]));
        }
        return needs;
    }

    /** Where to stand at each bank (banks.tsv): by its icon's tile, the tiles beside its booths, nearest the icon first. */
    static final RouteFiles.Table<Map<Integer, int[]>> BANKS = new RouteFiles.Table<>(() -> {
        var banks = new LinkedHashMap<Integer, List<Integer>>();
        for (var cells : Pathfinder.rows("/com/hdmapreforged/route/banks.tsv"))
        {
            int icon = cells.length < 2 ? -1 : parse(cells[0]);
            int stand = icon < 0 ? -1 : parse(cells[1]);
            if (stand >= 0)
            {
                banks.computeIfAbsent(icon, k -> new ArrayList<>()).add(stand);
            }
        }
        var stands = new HashMap<Integer, int[]>();
        banks.forEach((icon, list) -> stands.put(icon, list.stream().mapToInt(Integer::intValue).toArray()));
        return stands;
    });

    /** The booth or chest each stand of banks.tsv uses (its fifth column), by the stand. */
    static final RouteFiles.Table<Map<Integer, Integer>> BOOTHS = new RouteFiles.Table<>(() -> {
        var booths = new HashMap<Integer, Integer>();
        for (var cells : Pathfinder.rows("/com/hdmapreforged/route/banks.tsv"))
        {
            int stand = cells.length < 5 ? -1 : parse(cells[1]);
            int booth = stand < 0 ? -1 : parse(cells[4]);
            if (booth >= 0)
            {
                booths.putIfAbsent(stand, booth);
            }
        }
        return booths;
    });

    /** Where a bank step is named: on the booth or chest used from {@code stand}, else the stand. */
    static int booth(int stand)
    {
        return BOOTHS.get().getOrDefault(stand, stand);
    }

    /** Where to stand to use the bank whose icon is at {@code icon}: by each of its booths, else the icon itself. */
    static int[] bankStands(int icon)
    {
        var stands = BANKS.get().get(icon);
        return stands != null ? stands : new int[]{icon};
    }

    /** Where a boat can lie: the game's ports. */
    static final RouteFiles.Table<int[]> PORTS = new RouteFiles.Table<>(RouteSource::readPorts);

    static int[] readPorts()
    {
        try
        {
            return Ports.load().values().stream().mapToInt(Integer::intValue).toArray();
        }
        catch (IOException e)
        {
            return new int[0];
        }
    }

    /** gates.tsv by column: Name, Origin, Destination, Skills, Items, Quests, Varbits; {@code #} lines are comments. */
    static List<Gate> readGates()
    {
        return Pathfinder.table("/com/hdmapreforged/route/gates.tsv", cells -> {
            var from = point(cell(cells, 1));
            var to = point(cell(cells, 2));
            return from == null || to == null ? null : new Gate(from, to, cell(cells, 0), new Needs(cell(cells, 3),
                cell(cells, 4), cell(cells, 5), cell(cells, 6)));
        });
    }

    /** sea_passages.tsv by column: from x y, to x y, Name, Quests, Note; {@code #} lines are comments. */
    static List<SeaPassage> readSeaPassages()
    {
        return Pathfinder.table("/com/hdmapreforged/route/sea_passages.tsv", cells -> {
            var from = Pathfinder.ints(cell(cells, 0));
            var to = Pathfinder.ints(cell(cells, 1));
            return new SeaPassage(from[0], from[1], to[0], to[1], cell(cells, 2), new Needs("", "", cell(cells, 3), ""),
                cell(cells, 4));
        });
    }

    static String cell(String[] cells, int column)
    {
        return column < cells.length ? cells[column].trim() : "";
    }

    /** {@code "x y plane"}, or null. */
    static WorldPoint point(String cell)
    {
        var parts = cell.split("\\s+");
        try
        {
            return parts.length != 3 ? null
                : new WorldPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    static final Pattern ACTION = Pattern.compile("(Open|Enter|Exit|Climb|Travel|Pass|Cross|Go|Jump|Walk|Teleport|Use|Squeeze|Board|Ride|Crawl|Descend|Teeth-grip|Mine|Dig|Grapple|Search|Push|Move|Swing)\\b");

    void gates(Call call, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        for (Gate gate : GATES.get())
        {
            // Its quests and levels, and the items it takes (a key, a skavid map and a light) carried or worn.
            if (!usable(unlocks, gate.needs) || !state.items.has(gate.needs.items))
            {
                continue;
            }
            int a = snap(gate.from, gate.to);
            int b = snap(gate.to, gate.from);
            if (a >= 0 && b >= 0 && a != b)
            {
                // A row named by its action ("Climb-down Ladder (Mining Guild)") as it is; a door or gate opened.
                String name = ACTION.matcher(gate.name).lookingAt() ? gate.name : "Open " + gate.name;
                into.add(new Edge(a, b, Edge.Kind.ENTRANCE, name, detail(call, unlocks, null, gate.needs), 4));
            }
        }
    }

    /** The sea caves and the like (sea_passages.tsv) between blocks of sea the player's boat can sail. */
    void seaPassages(Call call, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        for (var passage : SEA_PASSAGES.get())
        {
            int from = seaAt(passage.fromX, passage.fromY);
            int to = seaAt(passage.toX, passage.toY);
            if (usable(unlocks, passage.needs) && sea.sailable(cellX(from), cellY(from), state.sailingLevel)
                && sea.sailable(cellX(to), cellY(to), state.sailingLevel))
            {
                // What it needs, and what the step should say besides (the boat's kind).
                String needs = detail(call, unlocks, null, passage.needs);
                String detail = needs == null ? passage.note : passage.note.isEmpty() ? needs : needs + ". " + passage.note;
                into.add(new Edge(from, to, Edge.Kind.ENTRANCE, passage.name, detail.isEmpty() ? null : detail, 10));
            }
        }
    }

    void shortcuts(Call call, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        for (var passage : shortcuts)
        {
            // Any of its ways the player meets: with a grapple, or bare-handed at a higher level.
            for (var way : passage.ways)
            {
                Needs needs = new Needs(way[0], way[1], way[2], way[3]);
                if (usable(unlocks, needs) && state.items.has(needs.items))
                {
                    Edge e = passage.edge;
                    into.add(new Edge(e.from, e.to, e.kind, e.name, detail(call, unlocks, null, needs), e.cost));
                    break;
                }
            }
        }
    }

    // Obelisks only with the Hard Wilderness Diary, which their stops need (it lets one choose where they lead; without
    // it they lead to a random other obelisk). Not entrances (map links, in Pathfinder, not our icons).
    static final Set<PoiType> TRANSPORTS = EnumSet.of(FAIRY_RING, SPIRIT_TREE,
        GNOME_GLIDER, BALLOON, QUETZAL, MUSHTREE, OBELISK, BOAT,
        CHARTER, CANOE, CARPET, MINECART, PORTAL, LEVER,
        TRANSPORT, AGILITY_SHORTCUT);

    static boolean isTransport(PoiType type)
    {
        return TRANSPORTS.contains(type);
    }

    boolean usable(Unlocks unlocks, Needs needs)
    {
        return unlocks == null || unlocks.usable(needs);
    }

    boolean walkable(WorldPoint point)
    {
        return map.walkable(point.getX(), point.getY(), point.getPlane());
    }

    int snap(WorldPoint point)
    {
        return snap(point, point);
    }

    int snap(WorldPoint point, WorldPoint other)
    {
        long key = (long) pack(point) << 32 | pack(other) & 0xffffffffL;
        // A spot one can stand on is where the game puts one, also in ground shut in but for the jump itself (the fairy
        // ring BIS in the zoo's unicorn pen): only a spot on an object or a wall moves to the nearest open ground.
        return snaps.computeIfAbsent(key, k -> walkable(point) ? pack(point) : map.nearestWalkable(point.getX(),
            point.getY(), point.getPlane(), SNAP, other.getX(), other.getY(), pocket));
    }

    /**
     * A teleport from anywhere ({@code into}); in a house a minigame teleport only from outside its portal
     * ({@code edges}): the game does not let one cast it in a player-owned house.
     */
    void teleport(Call call, Poi member, Unlocks unlocks, PlayerState state, List<Edge> into, List<Edge> edges)
    {
        if (HouseTracker.isTemplate(member.location.getX(), member.location.getY())
            || !usable(unlocks, member.needs) || !state.items.has(member.needs.items)
            || unlocks != null && unlocks.waiting(member.name, member.group, call.minute))
        {
            return;
        }
        int lacking = lacking(call, member.needs);
        int to = snap(member.location);
        // Outside one's own house only (a friend's house tells nothing of it).
        if (to < 0 || member.name.contains("(Outside)") && (state.houseExit < 0 || state.inHouse && !state.ownHouse
            || distance(to, state.houseExit) > PORT_RADIUS))
        {
            return;
        }
        String name = member.name.toLowerCase(ROOT);
        String category = member.group != null && !member.group.isEmpty() ? member.group : "Teleports";
        boolean once = onceOnly(name, member.group);
        if (call.avoids(name, category) || once && call.avoiding.contains(ONCE_ONLY))
        {
            return;
        }
        boolean outside = state.inHouse && "Minigame teleports".equals(member.group);
        int from = outside && state.houseExit >= 0 ? snap(RouteFeature.worldPoint(state.houseExit)) : Edge.ANYWHERE;
        if (outside && from < 0)
        {
            return;
        }
        (outside ? edges : into).add(new Edge(from, to, Edge.Kind.TELEPORT, member.name,
            detail(call, unlocks, TELEPORT, member.needs),
            // Half ticks: 4 ticks (a teleport spell, a tablet); a home or minigame teleport takes 24 ticks to cast.
            once ? 48 : 8, category)
            .weighed(call.saving.teleport + lacking + (charged(member.group, state) ? 1 : 0)));
    }

    /** Jewellery and such with charges: at a tie a spell's runes or a teleport without limit go first. */
    static final Set<String> CHARGED = Set.of("Ring of dueling", "Games necklace", "Combat bracelet", "Skills necklace",
        "Ring of wealth", "Amulet of glory", "Slayer ring", "Digsite pendant", "Necklace of passage", "Burning amulet",
        "Xeric's talisman", "Teleport crystal", "Pharaoh's sceptre", "Kharedst's memoirs", "Chronicle", "Skull sceptre",
        "Camulet");
    /** Their kinds without limit: the amulet of eternal glory, the eternal slayer ring. */
    static final Map<String, String> UNLIMITED = Map.of("Amulet of glory", "19707", "Slayer ring", "21268");

    static boolean charged(String group, PlayerState state)
    {
        // Set.of and Map.of take no null.
        if (group == null || !CHARGED.contains(group))
        {
            return false;
        }
        String unlimited = UNLIMITED.get(group);
        return unlimited == null || !state.items.has(unlimited);
    }

    /** The planted spirit trees that stand grown for this account (see {@link PlantedSpiritTrees}). */
    volatile Set<String> plantedTrees = Collections.emptySet();

    void plantedSpiritTrees(Set<String> grown)
    {
        plantedTrees = grown;
    }

    /** A planted spirit tree's stop the player has no grown tree at: not travelled from or to. */
    boolean notPlanted(Poi poi)
    {
        return PlantedSpiritTrees.planted(poi) && !plantedTrees.contains(poi.name);
    }

    void transport(Call call, Poi poi, Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        if (notPlanted(poi))
        {
            return;
        }
        // Only what is carried: a route never fetches an item (a fare's coins, a fairy ring's staff) from the bank.
        // (The bank only as a way past it first: BankDetour, a setting.)
        boolean exactEnds = poi.type == AGILITY_SHORTCUT && walkable(poi.location);
        if (!usable(unlocks, poi.needs) || !carried(unlocks, state, poi.type, poi.needs)
            || !exactEnds && snap(poi.location) < 0)
        {
            return;
        }
        var kind = kind(poi.type);
        for (var link : poi.links())
        {
            if (!usable(unlocks, link.needs) || !carried(unlocks, state, poi.type, link.needs)
                || poi.type == SPIRIT_TREE && call.notGrown.contains(link.point))
            {
                continue;
            }
            // Each end on its own side: stepping stones stand on lava or water, with pockets between them.
            boolean sameFloor = link.point.getPlane() == poi.location.getPlane();
            // A shortcut's ends are its two sides as given: kept where walkable, also inside ground shut in but for
            // shortcuts and gates (Gu'Tanoth behind its crumbling wall), which a snap would leave for open ground.
            int from = exactEnds ? pack(poi.location) : snap(poi.location, sameFloor ? link.point : poi.location);
            int to = poi.type == AGILITY_SHORTCUT && walkable(link.point) ? pack(link.point)
                : snap(link.point, sameFloor ? poi.location : link.point);
            if (to < 0 || to == from)
            {
                continue;
            }
            String name = describe(poi, link);
            // A shortcut left out before 0.3.2 was saved with its other side ("… → other side").
            if (call.avoids(name, poi.type.displayName) || poi.type == AGILITY_SHORTCUT && link.label != null
                && call.avoiding.contains((name + " → " + link.label).toLowerCase(ROOT)))
            {
                continue;
            }
            var fee = Fees.at(poi.location.getX(), poi.location.getY(), poi.location.getPlane());
            String needs = detail(call, unlocks, poi.type, link.needs);
            if (fee != null)
            {
                // The fee apart: it cannot be checked, so it is not something the player lacks.
                needs = needs == null ? "Needs: " + fee.text : needs + ". Also needs: " + fee.text;
            }
            into.add(new Edge(from, to, kind, name, needs, cost(poi.type) * dials(poi.type, link.label)
                + (fee == null ? 0 : fee.cost),
                poi.type.displayName).weighed(call.saving.tiles(poi.type)));
        }
    }

    static final String TYPE = "type:";

    static String typeKey(String category)
    {
        return TYPE + category.toLowerCase(ROOT);
    }

    static String describe(Poi poi, Poi.Link link)
    {
        String label = link.label == null || link.label.isEmpty() ? null : link.label;
        switch (poi.type)
        {
            // The obstacle is enough: its other side ("Other side", "Go over") says nothing.
            case AGILITY_SHORTCUT:
                return poi.name;
            case LEVER:
            case PORTAL:
                return poi.name + (label != null && !poi.name.contains(label) ? " → " + label : "");
            default:
                return poi.type.displayName + ": " + code(poi.type, label != null ? label : poi.name);
        }
    }

    /**
     * A fairy ring by the code one dials ("BIP"), not its whole title; codes dialled one after another ("AIR-DLR-DJQ-AJS",
     * the Fairy Resistance Hideout) as "AIR, DLR, DJQ, then AJS".
     */
    static String code(PoiType type, String name)
    {
        int dash = type == FAIRY_RING ? name.indexOf(" – ") : -1;
        String code = dash < 0 ? name : name.substring(0, dash);
        var codes = code.split("-");
        return dials(type, code) == 1 ? code
            : String.join(", ", Arrays.copyOf(codes, codes.length - 1)) + ", then " + codes[codes.length - 1];
    }

    /** How many rings one goes through to a stop: the codes of a sequence ("AIR-DLR-DJQ-AJS"), else one. */
    static int dials(PoiType type, String name)
    {
        if (type != FAIRY_RING || name == null)
        {
            return 1;
        }
        int dash = name.indexOf(" – ");
        String code = dash < 0 ? name : name.substring(0, dash);
        return code.matches("[A-Z]{3}(-[A-Z]{3})+") ? code.split("-").length : 1;
    }

    static Edge.Kind kind(PoiType type)
    {
        switch (type)
        {
            case BOAT:
            case CHARTER:
                return Edge.Kind.SHIP;
            case AGILITY_SHORTCUT:
            case PORTAL:
            case LEVER:
                return Edge.Kind.ENTRANCE;
            default:
                return Edge.Kind.TRANSPORT;
        }
    }

    /** In the avoid set: no home or minigame teleport at all (an order's trips without the one cast they get). */
    static final String ONCE_ONLY = TYPE + "home and minigame teleports";

    /** A home or minigame teleport: cast once, then its cooldown (30 and 20 minutes). {@code name} lower case. */
    static boolean onceOnly(String name, String group)
    {
        return name.contains("home teleport") || "Minigame teleports".equals(group);
    }

    /** Whether a route's step is such a teleport. */
    static boolean onceOnly(Route.Step step)
    {
        return step.kind == Route.Step.Kind.TELEPORT && step.name != null
            && onceOnly(step.name.toLowerCase(ROOT), step.category);
    }

    static int cost(PoiType type)
    {
        switch (type)
        {
            case FAIRY_RING:
            case SPIRIT_TREE:
            case MUSHTREE:
                return 10;
            case LEVER:
            case AGILITY_SHORTCUT:
            case OBELISK:
                return 8;
            case PORTAL:
                return 6;
            // A ship's trip is a few seconds of its map interlude, a boat's with its talk more.
            case MINECART:
            case GNOME_GLIDER:
            case QUETZAL:
                return 12;
            case CHARTER:
            case BALLOON:
                return 14;
            case CANOE:
                return 60;
            case CARPET:
                return 100;
            default:
                return 20;
        }
    }

    /**
     * A dock: leaving the boat needs its Sailing level; boarding needs the boat there, a shipwright to bring it, or a
     * port when no boat's port is known. {@code onBoat}: the route starts at sea on the boat, so it lies at no dock.
     */
    void mooring(Call call, Poi poi, Unlocks unlocks, PlayerState state, Options options, boolean onBoat, List<Edge> into)
    {
        if (!state.sailing || state.sailingLevel < level(poi.needs) || !usable(unlocks, poi.needs))
        {
            return;
        }
        int x = poi.location.getX();
        int y = poi.location.getY();
        int land = map.nearestWalkable(x, y, 0, 8);
        int water = sea.nearestBlock(x, y, DOCK_REACH, state.sailingLevel);
        if (land < 0 || water < 0)
        {
            return;
        }
        into.add(new Edge(water, land, Edge.Kind.DISEMBARK, "Dock at " + poi.name + dockLevel(poi), null, 10));
        int needed = Math.max(1, level(poi.needs));
        String lacking = call.realSailing < needed
            ? "You lack: " + (call.realSailing <= 0 ? "Sailing" : needed + " Sailing")
            : null;
        String name = "Board your boat at " + poi.name;
        String hint = null;
        int cost = 10;
        String summon = Arrays.stream(state.boatFocus).anyMatch(focus -> focus >= 1)
            ? boatSpell(unlocks, state, SUMMON_BOAT, "31441", "Summon boat tablet") : null;
        if (Arrays.stream(state.boats).anyMatch(boat -> distance(boat, land) <= BOAT_REACH))
        {
            name += dockLevel(poi);
        }
        else if (summon != null)
        {
            name = "Summon your boat and board it at " + poi.name;
            hint = summon;
            cost = 16;
        }
        else if (Arrays.stream(options.shipwrights).anyMatch(s -> distance(s, land) <= SHIPWRIGHT_REACH))
        {
            hint = "Ask the shipwright here to bring your boat (fee)";
            cost = 60;
        }
        else if (state.boats.length == 0 && !onBoat
            && Arrays.stream(PORTS.get()).anyMatch(port -> distance(port, land) <= SHIPWRIGHT_REACH))
        {
            // Where the boat lies is not known yet (not logged in): a port, where it may lie; not any dock.
            hint = "If your boat is moored here (a shipwright can bring it)";
        }
        else
        {
            return;
        }
        into.add(new Edge(land, water, Edge.Kind.BOARD, name, lacking != null ? lacking : hint, cost));
    }

    static String dockLevel(Poi poi)
    {
        int level = level(poi.needs);
        return level > 1 ? " (" + level + " Sailing)" : "";
    }

    /**
     * The boat spells (standard spellbook): Summon Boat brings a boat with a teleport focus to the dock one stands at,
     * Teleport to Boat takes one to a boat with the greater focus.
     */
    static final Needs SUMMON_BOAT = new Needs("56 Magic", "557=1&&555=1&&563=2", "", "4070=0");
    static final Needs TELEPORT_TO_BOAT = new Needs("67 Magic", "557=2&&555=2&&563=2", "", "4070=0");

    /** How the player casts a boat spell now: its tablet (item id), the spell itself, or null when neither. */
    String boatSpell(Unlocks unlocks, PlayerState state, Needs spell, String tablet, String tabletName)
    {
        return state.items.has(tablet) ? tabletName
            : usable(unlocks, spell) && state.items.has(spell.items) ? "Spell (standard spellbook)" : null;
    }

    /** To each boat with the greater teleport focus: by the Sailing cape, a tablet or the spell, when the player can. */
    void teleportToBoat(Unlocks unlocks, PlayerState state, List<Edge> into)
    {
        String means = !state.sailing ? null : state.items.has("31288||31290") ? "Sailing cape"
            : boatSpell(unlocks, state, TELEPORT_TO_BOAT, "31443", "Teleport to boat tablet");
        for (int i = 0; means != null && i < state.boats.length; i++)
        {
            int land = state.boatFocus[i] < 2 ? -1
                : map.nearestWalkable(x(state.boats[i]), y(state.boats[i]), 0, 12);
            if (land >= 0)
            {
                into.add(new Edge(Edge.ANYWHERE, land, Edge.Kind.TELEPORT, "Teleport to Boat", means, 10));
            }
        }
    }

    /**
     * Whether what a step needs is carried. A fairy ring's staff (Dramen or Lunar) is not needed once the Lumbridge &
     * Draynor Elite Diary is done.
     */
    static boolean carried(Unlocks unlocks, PlayerState state, PoiType type, Needs needs)
    {
        return state.items.has(needs.items) || type == FAIRY_RING && unlocks != null && unlocks.lumbridgeElite();
    }

    static int level(Needs needs)
    {
        Matcher m = SAILING.matcher(needs.skills);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /** The fallback takes a teleport whose item the player lacks only as a last resort (else all of it would be red). */
    static int lacking(Call call, Needs needs)
    {
        return call.fallback && call.missingItems != null && !needs.items.isEmpty()
            && !call.missingItems.has(needs.items) ? LACKING_ITEM : 0;
    }

    static boolean stairs(String thing)
    {
        return HouseTracker.feature(thing) == null && !thing.equals("Portal") && !thing.startsWith(NEXUS);
    }

    /** Into one's own house, best first: the capes cost nothing. */
    static final String[] INTO_HOUSE = {"Construction cape: Tele to POH", "Max cape: Home", "Teleport to House",
        "Teleport to house tablet"};
    static final Needs[] INTO_HOUSE_NEEDS = {new Needs("", "9789||9790", "", ""),
        new Needs("", "13280||13342", "", ""), new Needs("40 Magic", "563=1&&556=1&&557=1", "", "4070=0"),
        new Needs("", "8013", "", "")};

    /**
     * The house's ways out. With its plan seen (own house) they stand where they are on it, routes walk to them and
     * teleport in to its arrival; else, in the house, the route starts with them, and from outside a teleport in or
     * its portal leads to them at once.
     */
    void house(Call call, List<Poi> pois, Unlocks unlocks, PlayerState state, Map<String, Poi> teleports,
        List<Edge> starts, List<Edge> edges)
    {
        var plan = state.inHouse && !state.ownHouse ? null : map.house();
        var into = new ArrayList<Edge>();
        var portal = state.houseExit < 0 ? null
            : new WorldPoint(x(state.houseExit), y(state.houseExit), 0);
        if (state.inHouse && portal != null && (plan == null || !plan.things.containsValue("Portal")))
        {
            addHouse(into, portal, "Leave the house by its portal", 6, null);
        }
        var placed = new HashSet<String>();
        if (plan != null)
        {
            plan.things.forEach((node, name) -> {
                // Each side: the route takes the one the game walks to as it is clicked (Pathfinder.steps).
                int[] at = plan.approach(node);
                var out = new ArrayList<Edge>();
                List<String> features = name.startsWith(NEXUS + ": ") ? Arrays.stream(name.substring(NEXUS.length() + 2)
                    .split(", ")).map(place -> "nexus:" + place).collect(Collectors.toList())
                    : name.startsWith(NEXUS) ? List.of() : Collections.singletonList(HouseTracker.feature(name));
                // A spiritual fairy tree is both: of it what the settings have (only its ring, say).
                if (features.contains(HouseSettings.BOTH) && !state.houseFeatures.contains(HouseSettings.BOTH))
                {
                    features = List.of(HouseSettings.GARDENS[1], HouseSettings.GARDENS[2]);
                }
                // As set for the account (the panel may have taken one off); one not reachable is used from the arrival.
                // Each of several alike (two spirit trees) from where it stands: the route takes the nearest.
                for (String feature : features)
                {
                    if (feature != null && at.length > 0 && HouseSettings.has(state.houseFeatures, feature))
                    {
                        placed.add(feature);
                        ways(call, feature, pois, unlocks, state, teleports, out);
                    }
                }
                if (name.equals("Portal") && portal != null)
                {
                    addHouse(out, portal, "Leave the house by its portal", 6, null);
                }
                for (int dz = -1; stairs(name) && dz <= 1; dz += 2)
                {
                    int other = z(node) + dz;
                    // Only to a floor with stairs where these stand: they go up or down, seldom both.
                    if (plan.things.entrySet().stream().anyMatch(t -> z(t.getKey()) == other
                        && distance(t.getKey(), node) <= 3 && stairs(t.getValue())))
                    {
                        out.add(new Edge(Edge.ANYWHERE, map.nearestWalkable(x(node), y(node), other, 3),
                            Edge.Kind.STAIRS, name, null, 5));
                    }
                }
                for (Edge e : out)
                {
                    for (int side : e.to >= 0 ? at : new int[0])
                    {
                        edges.add(e.withFrom(side));
                    }
                }
            });
        }
        // Not seen on the plan (typed in by hand), or no plan: usable at once in the house.
        for (String feature : state.inHouse && !state.ownHouse ? Set.<String>of() : state.houseFeatures)
        {
            // "Both" is a ring and a tree, maybe in two gardens: only a part not on the plan is used at once.
            boolean both = feature.equals(HouseSettings.BOTH) && !placed.contains(feature);
            for (String part : both ? List.of(HouseSettings.GARDENS[1], HouseSettings.GARDENS[2]) : List.of(feature))
            {
                if (!HouseSettings.has(placed, part))
                {
                    ways(call, part, pois, unlocks, state, teleports, into);
                }
            }
        }
        if (plan != null)
        {
            into.forEach(e -> edges.add(e.withFrom(plan.arrival)));
            into.clear();
        }
        if (state.inHouse)
        {
            starts.addAll(into);
            return;
        }
        int best = -1;
        int lacking = 0;
        for (int i = 0; i < INTO_HOUSE.length; i++)
        {
            Needs needs = INTO_HOUSE_NEEDS[i];
            int lack = lacking(call, needs);
            // Set to land outside, the capes do so; the spell and the tablet keep an option to land inside.
            if ((state.landsInside || i >= 2) && usable(unlocks, needs) && state.items.has(needs.items)
                && (best < 0 || lack < lacking)
                && !call.avoids(INTO_HOUSE[i] + (state.landsInside ? "" : " (inside)"), "Teleports"))
            {
                best = i;
                lacking = lack;
            }
        }
        int door = portal == null ? -1 : snap(portal);
        String teleport = best < 0 ? null : INTO_HOUSE[best] + (state.landsInside ? "" : " (Inside)");
        if (plan != null)
        {
            if (best >= 0)
            {
                starts.add(new Edge(Edge.ANYWHERE, plan.arrival, Edge.Kind.TELEPORT, teleport,
                    detail(call, unlocks, TELEPORT, INTO_HOUSE_NEEDS[best]), 10, "Teleports")
                    .weighed(call.saving.teleport + lacking));
            }
            if (door >= 0)
            {
                edges.add(new Edge(door, plan.arrival, Edge.Kind.ENTRANCE, "Enter your house", null, 6));
            }
            return;
        }
        for (Edge e : into)
        {
            if (best >= 0)
            {
                starts.add(new Edge(Edge.ANYWHERE, e.to, Edge.Kind.HOUSE, teleport + " → " + e.name,
                    both(detail(call, unlocks, TELEPORT, INTO_HOUSE_NEEDS[best]), e.detail), 10 + e.cost)
                    .weighed(call.saving.teleport + lacking));
            }
            if (door >= 0)
            {
                edges.add(new Edge(door, e.to, Edge.Kind.HOUSE, "Enter your house → " + e.name, e.detail,
                    6 + e.cost));
            }
        }
    }

    /** Two details of one step (the way into the house and what is used there): what the bank gives as one line. */
    static String both(String a, String b)
    {
        String bank = BankDetour.FROM_BANK;
        if (a == null || b == null)
        {
            return a == null ? b : a;
        }
        return a.startsWith(bank) && b.startsWith(bank) ? a + ", " + b.substring(bank.length()) : a + ". " + b;
    }

    static final String NEXUS = "Portal Nexus";
    /** How a mounted amulet's house step starts, in {@link HouseSettings#MOUNTED}'s order. */
    static final String[] MOUNTED_NAMES = {"Mounted glory: ", "Mounted talisman: ", "Mounted pendant: "};

    /** Where a feature of the house ("portal:Varrock", "box:ornate", "glory", "xeric", "fairy ring") leads, from anywhere. */
    void ways(Call call, String feature, List<Poi> pois, Unlocks unlocks, PlayerState state,
        Map<String, Poi> teleports, List<Edge> into)
    {
        int mounted = Arrays.asList(HouseSettings.MOUNTED).indexOf(feature);
        if (feature.startsWith("portal:") || feature.startsWith("nexus:"))
        {
            String place = feature.substring(feature.indexOf(':') + 1);
            var teleport = portalTeleport(place, teleports);
            if (teleport != null)
            {
                // Named as the portal is ("Troll Stronghold"), not as the teleport that says where it leads.
                addHouse(into, teleport.location, (feature.startsWith("nexus:") ? "Portal nexus: " : "House portal: ")
                    + place, 6, null);
            }
        }
        else if (feature.startsWith("box:") || mounted >= 0)
        {
            var groups = mounted >= 0 ? List.of(HouseSettings.MOUNTED_GROUPS[mounted])
                : List.of("Ring of dueling", "Games necklace", "Combat bracelet", "Skills necklace", "Ring of wealth",
                "Amulet of glory").subList(0, feature.equals("box:basic") ? 2 : feature.equals("box:ornate") ? 6 : 4);
            for (var teleport : teleports.values())
            {
                if (teleport.group != null && groups.contains(teleport.group) && usable(unlocks, teleport.needs))
                {
                    addHouse(into, teleport.location, (mounted >= 0 ? MOUNTED_NAMES[mounted] : "Jewellery box: ")
                        + teleport.name, 8, null);
                }
            }
        }
        if (feature.equals("obelisk"))
        {
            // As those in the Wilderness: to the obelisk one chooses, with the Hard Wilderness Diary.
            for (var poi : pois)
            {
                if (poi.type == OBELISK && usable(unlocks, poi.needs))
                {
                    addHouse(into, poi.location, "House obelisk: " + poi.name, cost(OBELISK), null);
                }
            }
        }
        if (feature.contains("fairy ring") || feature.contains("spirit tree"))
        {
            for (var poi : pois)
            {
                boolean ring = poi.type == FAIRY_RING && feature.contains("fairy ring");
                boolean tree = poi.type == SPIRIT_TREE && feature.contains("spirit tree");
                // The house's ring needs the staff as any ring does.
                if ((ring || tree) && usable(unlocks, poi.needs) && carried(unlocks, state, poi.type, poi.needs)
                    && !notPlanted(poi))
                {
                    addHouse(into, poi.location, "House " + poi.type.displayName.toLowerCase(ROOT) + ": "
                        + code(poi.type, poi.name), 10 * dials(poi.type, poi.name), bankNeeds(call, unlocks, poi.type,
                        poi.needs));
                }
            }
        }
    }

    /** Portal and nexus places the spellbooks name otherwise. */
    static final Map<String, String> PORTAL_NAMES = Map.of("lunar isle", "moonclan", "marim",
        "ape atoll teleport (standard)", "ape atoll dungeon", "ape atoll teleport (arceuus)", "waterbirth island",
        "waterbirth", "carrallangar", "carrallanger");

    /** A spell starting with the place's name first, else a teleport to it by name ("Stony basalt: Troll Stronghold"). */
    static Poi portalTeleport(String place, Map<String, Poi> teleports)
    {
        String given = place.trim().toLowerCase(ROOT);
        String wanted = PORTAL_NAMES.getOrDefault(given, given);
        int bestRank = 0;
        String bestName = null;
        Poi best = null;
        for (var entry : teleports.entrySet())
        {
            String name = entry.getKey();
            var teleport = entry.getValue();
            boolean spell = name.startsWith(wanted) && teleport.group != null && teleport.group.contains("Spellbook");
            if (!spell && !name.contains(": " + wanted))
            {
                continue;
            }
            if (name.equals(wanted) || name.equals(wanted + " teleport"))
            {
                return teleport;
            }
            // Spells first, then the shortest name, then the first alphabetically.
            int rank = (spell ? 0 : 1 << 16) + name.length();
            if (best == null || rank < bestRank || rank == bestRank && name.compareTo(bestName) < 0)
            {
                bestRank = rank;
                bestName = name;
                best = teleport;
            }
        }
        return best;
    }

    void addHouse(List<Edge> into, WorldPoint point, String name, int cost, String detail)
    {
        int to = snap(point);
        if (to >= 0)
        {
            // One kind (as minigame teleports are), to leave all out at once; the way out of the house is none.
            into.add(new Edge(Edge.ANYWHERE, to, Edge.Kind.HOUSE, name, detail, cost, name.contains(": ")
                ? "House teleports" : null));
        }
    }

    static final int LACKING_ITEM = 2000;

    static String needsText(Call call, Needs needs)
    {
        if (needs.isEmpty())
        {
            return null;
        }
        if (call.missingLevels == null && call.missingItems == null)
        {
            return uncheckedText(call.realUnlocks, needs);
        }
        var parts = new ArrayList<String>();
        if (call.missingLevels != null)
        {
            parts.addAll(unmetLines(call.missingLevels, needs));
        }
        if (call.missingItems != null && !call.missingItems.has(needs.items))
        {
            parts.add(missingItems(call.missingItems, needs.items, call.itemNames));
        }
        return parts.isEmpty() ? null : "You lack: " + String.join(", ", parts);
    }

    /** The skill/quest lines of {@code needs} that {@code unlocks} says are not met, by their text. */
    static List<String> unmetLines(Unlocks unlocks, Needs needs)
    {
        return Requirements.skillsAndQuests(needs).stream().filter(line -> Boolean.FALSE.equals(unlocks.met(line)))
            .map(line -> line.text).collect(Collectors.toList());
    }

    /** The groups of a column like {@code "563=1&&556=3"} that are not carried, by name: "1 × Law rune". */
    static String missingItems(ItemSnapshot carried, String column, IntFunction<String> names)
    {
        var missing = Arrays.stream(ItemSnapshot.ALL.split(column)).filter(all -> !carried.has(all))
            .collect(Collectors.toList());
        return itemLines(missing, names, "items you do not have");
    }

    /** A step's detail: what it needs from the bank ({@link #bankNeeds}), else {@link #needsText}. */
    static String detail(Call call, Unlocks unlocks, PoiType type, Needs needs)
    {
        String bank = bankNeeds(call, unlocks, type, needs);
        return bank != null ? bank : needsText(call, needs);
    }

    /**
     * What a step needs from the bank, when the bank is counted and what is carried is not enough; else null. A fairy
     * ring needs no staff after the Lumbridge & Draynor Elite Diary.
     */
    static String bankNeeds(Call call, Unlocks unlocks, PoiType type, Needs needs)
    {
        boolean enough = call.carried == null || call.carried.has(needs.items)
            || type == FAIRY_RING && unlocks != null && unlocks.lumbridgeElite();
        return enough ? null : BankDetour.FROM_BANK + fromBank(call, needs.items);
    }

    /**
     * What of {@code column} only the bank has, by name: of each part not carried, the alternatives the bank holds
     * ("Dramen staff", "Law rune, Air rune ×3").
     */
    static String fromBank(Call call, String column)
    {
        var parts = new ArrayList<String>();
        for (String all : ItemSnapshot.ALL.split(column))
        {
            if (!call.carried.has(all))
            {
                var held = new ArrayList<String>();
                for (String any : ItemSnapshot.ANY.split(all))
                {
                    if (call.items.has(any))
                    {
                        held.add(any.trim());
                    }
                }
                parts.add(String.join("||", held.isEmpty() ? List.of(all) : held.subList(0, 1)));
            }
        }
        return itemLines(parts, call.itemNames, "items");
    }

    static String itemLines(List<String> all, IntFunction<String> names, String none)
    {
        var lines = names == null ? List.<String>of() : Requirements.items(String.join("&&", all), names);
        return lines.isEmpty() ? none : String.join(", ", lines);
    }

    /** A step's requirements when not all could be checked (logged out, unknown quest or item); else null. */
    static String uncheckedText(Unlocks realUnlocks, Needs needs)
    {
        var lines = Requirements.skillsAndQuests(needs);
        if (lines.stream().allMatch(line -> realUnlocks != null && realUnlocks.met(line) != null))
        {
            return null;
        }
        var parts = lines.stream().map(line -> line.text).collect(Collectors.toList());
        if (!needs.items.trim().isEmpty() && realUnlocks == null)
        {
            parts.add("items");
        }
        return parts.isEmpty() ? null : "Needs: " + String.join(", ", parts);
    }
}
