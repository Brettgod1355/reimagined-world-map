package com.hdmapreforged;

import com.google.gson.stream.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * What the search finds, as our map server works it out once for every player from the lists it takes from the wiki
 * each week (its search.json, see {@code MapServer}): each NPC name's places with how many stand there or the way in,
 * the kind most seen and what they drop; each item's shops, spots on the ground and droppers; the shops' wares; the
 * names to suggest; and which map draws each place where maps overlap, for the map version it names. Immutable.
 */
final class WorldData
{
    /** The data version's file read. */
    static final String FILE = "search";
    /** Of search.json: a file in another format (an older or newer map server) is not read. */
    static final int FORMAT = 1;
    static final WorldData NONE = new WorldData(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(),
        new String[0], new String[0], null, Collections.emptyMap());
    /** A shop is where the server puts it: a shop icon or a card this near is that shop. */
    static final int SHOP_RADIUS = 12;
    /** The most wares of a shop. */
    static final int MAX_ITEMS = 200;
    private static final int MAX_ID = 0xffff;
    private static final int MAX_COORDINATE = 0x3fff;
    private static final int MAX_COUNT = 1_000_000;
    private static final int MAX_WORDS = 24;
    private static final int MAX_NAME = 64;
    /** What {@link #number} gives for anything but a number in range. */
    private static final int BAD = Integer.MIN_VALUE;
    /** A shop that never runs out. */
    static final int ENDLESS = -1;

    @RequiredArgsConstructor
    static final class Shop
    {
        final String name;
        final WorldPoint point;
        /** Its shopkeeper's NPC id, for the picture; -1 when not known. */
        final int npc;
        /** What it sells, with how many it keeps while no one trades ({@link #ENDLESS}). */
        final List<ItemSources.Ware> wares;
    }

    @RequiredArgsConstructor
    static final class Drop
    {
        final int item;
        /** The game's name of the item. */
        final String name;
        /** The wiki's words: "Always", "3/128", "Common". */
        final String rarity;
        /** "5-10", "7"; empty for one. */
        final String quantity;
        /** The part of the drop table: "a" always, "r" the rare drop table, "t" tertiary, "" the main table. */
        final String part;
        /** The monster's version this row is for when not the table's own ("Catacombs of Kourend"); else "". */
        final String version;
    }

    /** One of a monster's drop tables: the monster's version ("Wilderness Slayer Cave"), a kind of loot. */
    @RequiredArgsConstructor
    static final class Table
    {
        /** Empty for a page's only table. */
        final String name;
        final List<Drop> drops;
    }

    final List<Shop> shops;
    /** By {@link #key}. */
    private final Map<String, NpcSpawns> npcs;
    private final Map<String, ItemSources> items;
    /** The names the suggestions choose from, with their lower-case forms in the same order ({@link #suggestions}). */
    private final String[][] npcChoices;
    private final String[][] itemChoices;
    /** Lower-case names the search finds: NPCs standing somewhere or with a way in ({@link #monsterName}). */
    private final Set<String> monsterNames = new HashSet<>();
    /** The map version {@link #maps} is for; null for none. */
    private final String mapVersion;
    /** Which map draws a place where maps overlap, by map id; null for none. */
    private final Map<WorldPoint, Integer> maps;
    private BaseMaps drawnFor;
    private Map<WorldPoint, BaseMap> drawn = Collections.emptyMap();

    private WorldData(Map<String, NpcSpawns> npcs, Map<String, ItemSources> items, List<Shop> shops, String[] npcNames,
        String[] itemNames, String mapVersion, Map<WorldPoint, Integer> maps)
    {
        this.npcs = npcs;
        this.items = items;
        this.shops = shops;
        npcChoices = choices(npcNames);
        itemChoices = choices(itemNames);
        monsterNames.addAll(Arrays.asList(npcChoices[1]));
        this.mapVersion = mapVersion;
        this.maps = maps;
    }

    /**
     * search.json, read a row at a time: a row with anything malformed or out of range is left out, and a file broken
     * part-way keeps the rows before the break. Null when it is not search.json in {@link #FORMAT}.
     */
    static WorldData parse(byte[] file)
    {
        Object[] format = new Object[2];
        Map<String, NpcSpawns> npcs = new HashMap<>();
        Map<String, ItemSources> items = new HashMap<>();
        List<Shop> shops = new ArrayList<>();
        List<String[]> names = new ArrayList<>(Arrays.asList(new String[0], new String[0]));
        Map<WorldPoint, Integer> maps = new HashMap<>();
        try (JsonReader in = new JsonReader(MapServer.text(file)))
        {
            in.beginObject();
            while (in.hasNext())
            {
                switch (in.nextName())
                {
                    case "format":
                        format[0] = value(in);
                        break;
                    case "map":
                        format[1] = value(in);
                        break;
                    case "npcs":
                        rows(in, o -> npc(o, npcs));
                        break;
                    case "items":
                        rows(in, o -> item(o, items));
                        break;
                    case "shops":
                        rows(in, o -> shop(o, shops));
                        break;
                    case "npcNames":
                        names.set(0, texts(value(in)));
                        break;
                    case "itemNames":
                        names.set(1, texts(value(in)));
                        break;
                    case "drawn":
                        rows(in, o -> {
                            int map = number(o.get("map"), -1, MAX_ID);
                            List<WorldPoint> points = map == BAD ? null : points(o.get("at"));
                            (points == null ? Collections.<WorldPoint>emptyList() : points).forEach(p -> maps.put(p, map));
                        });
                        break;
                    case "undrawn":
                        List<WorldPoint> points = points(value(in));
                        (points == null ? Collections.<WorldPoint>emptyList() : points).forEach(p -> maps.put(p, null));
                        break;
                    default:
                        in.skipValue();
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            // What was read stays: a file broken part-way keeps the rows before the break.
        }
        if (!Double.valueOf(FORMAT).equals(format[0]))
        {
            return null;
        }
        return new WorldData(npcs, items, Collections.unmodifiableList(shops), names.get(0), names.get(1),
            format[1] instanceof String ? (String) format[1] : null, maps);
    }

    /** {@code {name, npc, places: [{at, n | e}], tables: [{v, items}]}}: NPCs of a name, as {@link #spawns} finds them. */
    private static void npc(Map<?, ?> o, Map<String, NpcSpawns> npcs)
    {
        String name = text(o, "name");
        int npc = number(o.get("npc"), 0, MAX_ID);
        List<NpcSpawns.Group> groups = new ArrayList<>();
        for (Object e : list(o, "places"))
        {
            Map<?, ?> place = map(e);
            List<WorldPoint> points = points(place.get("at"));
            String entrance = text(place, "e");
            int seen = number(place.get("n"), 1, MAX_COUNT);
            if (points == null || entrance.length() > MAX_NAME || entrance.isEmpty() && seen == BAD)
            {
                return;
            }
            NpcSpawns.Group group = new NpcSpawns.Group(name, "", points);
            group.entrance = !entrance.isEmpty();
            group.seen = group.entrance ? 0 : seen;
            groups.add(group.withNote(group.entrance ? "Entrance: " + entrance : seen + " here"));
        }
        List<Table> tables = new ArrayList<>();
        for (Object e : list(o, "tables"))
        {
            String table = text(map(e), "v");
            List<Drop> drops = drops(map(e));
            if (drops == null || table.length() > MAX_NAME)
            {
                return;
            }
            tables.add(new Table(table, drops));
        }
        if (named(name) && npc != BAD && !groups.isEmpty())
        {
            npcs.put(key(name), new NpcSpawns(name, groups, npc, tables));
        }
    }

    /** {@code [{id, name, r, q, t, v}]}; null when any drop is malformed. */
    private static List<Drop> drops(Map<?, ?> o)
    {
        List<Drop> drops = new ArrayList<>();
        for (Object d : list(o, "items"))
        {
            Map<?, ?> drop = map(d);
            int item = number(drop.get("id"), 0, MAX_ID);
            String name = text(drop, "name");
            String rarity = text(drop, "r");
            String quantity = text(drop, "q");
            String part = text(drop, "t");
            String version = text(drop, "v");
            if (item == BAD || !named(name) || rarity.isEmpty() || rarity.length() > MAX_WORDS || quantity.length() > MAX_WORDS
                || part.length() > 1 || !"art".contains(part) || version.length() > MAX_NAME)
            {
                return null;
            }
            drops.add(new Drop(item, name, rarity, quantity, part, version));
        }
        return drops;
    }

    /**
     * {@code {name, id, shops: [{shop, at, n}], ground: [{at, n}], drops: [{npc, name, r, q, v, places, table}]}}: where
     * an item comes from, as {@link #item} finds it.
     */
    private static void item(Map<?, ?> o, Map<String, ItemSources> items)
    {
        String name = text(o, "name");
        int item = number(o.get("id"), 0, MAX_ID);
        List<ItemSources.Store> stores = new ArrayList<>();
        for (Object e : list(o, "shops"))
        {
            List<WorldPoint> at = points(map(e).get("at"));
            String shop = text(map(e), "shop");
            int stock = stock(map(e).get("n"));
            if (at == null || at.size() != 1 || !named(shop) || stock == 0)
            {
                return;
            }
            stores.add(new ItemSources.Store(shop, at.get(0), stock));
        }
        List<ItemSources.Ground> grounds = new ArrayList<>();
        for (Object e : list(o, "ground"))
        {
            List<WorldPoint> at = points(map(e).get("at"));
            int count = number(map(e).get("n"), 1, MAX_COUNT);
            if (at == null || at.size() != 1 || count == BAD)
            {
                return;
            }
            grounds.add(new ItemSources.Ground(at.get(0), count));
        }
        List<ItemSources.Dropper> droppers = new ArrayList<>();
        for (Object e : list(o, "drops"))
        {
            Map<?, ?> d = map(e);
            int npc = number(d.get("npc"), 0, MAX_ID);
            String npcName = text(d, "name");
            String rarity = text(d, "r");
            String quantity = text(d, "q");
            String version = text(d, "v");
            String table = text(d, "table");
            int places = number(d.get("places"), 1, MAX_COUNT);
            if (npc == BAD || !named(npcName) || rarity.isEmpty() || rarity.length() > MAX_WORDS
                || quantity.length() > MAX_WORDS || version.length() > MAX_NAME || table.length() > MAX_NAME || places == BAD)
            {
                return;
            }
            droppers.add(new ItemSources.Dropper(npc, npcName, rarity, version, quantity, places, table));
        }
        if (named(name) && item != BAD && !(stores.isEmpty() && grounds.isEmpty() && droppers.isEmpty()))
        {
            items.put(key(name), new ItemSources(name, item, stores, grounds, droppers));
        }
    }

    /** {@code {name, at, npc, items: [{id, name, n}]}}: a shop with its wares by name. */
    private static void shop(Map<?, ?> o, List<Shop> shops)
    {
        String name = text(o, "name");
        List<WorldPoint> at = points(o.get("at"));
        List<?> listed = list(o, "items");
        List<ItemSources.Ware> wares = new ArrayList<>();
        for (Object e : listed)
        {
            int item = number(map(e).get("id"), 0, MAX_ID);
            String itemName = text(map(e), "name");
            int stock = stock(map(e).get("n"));
            if (item == BAD || !named(itemName) || stock == 0)
            {
                return;
            }
            wares.add(new ItemSources.Ware(itemName, item, stock));
        }
        if (named(name) && at != null && at.size() == 1 && listed.size() <= MAX_ITEMS)
        {
            shops.add(new Shop(name, at.get(0), Math.max(-1, number(o.get("npc"), 0, MAX_ID)),
                Collections.unmodifiableList(wares)));
        }
    }

    /**
     * Where NPCs of this name stand (whatever its case), close spots as one place with how many stand there, and what
     * they drop; null when none stands anywhere known, as the search finds only those.
     */
    NpcSpawns spawns(String name)
    {
        return npcs.get(key(name));
    }

    /** Where this item comes from: the shops that sell it, where it lies, what drops it; null when nothing is known. */
    ItemSources item(String name)
    {
        return items.get(key(name));
    }

    /** The shop near {@code at}; null when none is known there. */
    Shop shop(WorldPoint at)
    {
        Shop best = null;
        for (Shop shop : shops)
        {
            if (shop.point.distanceTo(at) <= SHOP_RADIUS
                && (best == null || shop.point.distanceTo(at) < best.point.distanceTo(at)))
            {
                best = shop;
            }
        }
        return best;
    }

    /** What the shop near {@code at} sells, with how many it keeps; null when no shop is known there. */
    List<ItemSources.Ware> wares(WorldPoint at)
    {
        Shop shop = shop(at);
        return shop == null ? null : shop.wares;
    }

    /**
     * Which map draws each place where maps overlap, for these maps: none unless they are of the map version the
     * server worked it out for, and then the region table decides alone; a place mapped to null is drawn by none.
     */
    synchronized Map<WorldPoint, BaseMap> drawnBy(BaseMaps baseMaps)
    {
        if (baseMaps != drawnFor)
        {
            drawnFor = baseMaps;
            drawn = new HashMap<>();
            if (baseMaps != null && mapVersion != null && mapVersion.equals(baseMaps.version()))
            {
                maps.forEach((point, id) -> {
                    BaseMap map = id == null ? null : baseMaps.byId(id);
                    if (id == null || map != null)
                    {
                        drawn.put(point, map);
                    }
                });
            }
        }
        return drawn;
    }

    /**
     * A map label that names a monster or NPC the search finds ("Abyssal demons", "The Nightmare"): the monster search
     * shows where they are, so the place search need not list the label as a place too.
     */
    boolean monsterName(String label)
    {
        String name = label.trim().toLowerCase(Locale.ROOT);
        return monsterNames.contains(name) || name.endsWith("s") && monsterNames.contains(name.substring(0,
            name.length() - 1));
    }

    /** NPC names starting with or containing {@code typed}, starts first. */
    List<String> npcSuggestions(String typed, int limit)
    {
        return suggestions(npcChoices, typed, limit);
    }

    /** Names of items some shop sells, that lie somewhere or that something drops, likewise. */
    List<String> itemSuggestions(String typed, int limit)
    {
        return suggestions(itemChoices, typed, limit);
    }

    /** The names as the server orders them (shortest first, then alphabetically), with their lower-case forms. */
    private static String[][] choices(String[] names)
    {
        return new String[][]{names, Arrays.stream(names).map(n -> n.toLowerCase(Locale.ROOT)).toArray(String[]::new)};
    }

    /** Of {@link #choices}, those starting with {@code typed}, then those holding it: one pass, nothing sorted. */
    private static List<String> suggestions(String[][] choices, String typed, int limit)
    {
        String t = typed.trim().toLowerCase(Locale.ROOT);
        List<String> starts = new ArrayList<>();
        List<String> holds = new ArrayList<>();
        for (int i = 0; i < choices[0].length && starts.size() < limit; i++)
        {
            if (choices[1][i].startsWith(t))
            {
                starts.add(choices[0][i]);
            }
            else if (holds.size() < limit && choices[1][i].contains(t))
            {
                holds.add(choices[0][i]);
            }
        }
        starts.addAll(holds);
        return starts.subList(0, Math.min(limit, starts.size()));
    }

    /** A name as the search compares names: letter by letter without case, as {@code equalsIgnoreCase} does. */
    static String key(String name)
    {
        char[] letters = name.trim().toCharArray();
        for (int i = 0; i < letters.length; i++)
        {
            letters[i] = Character.toLowerCase(Character.toUpperCase(letters[i]));
        }
        return new String(letters);
    }

    /** Each object of an array in turn, never the whole array at once. */
    private static void rows(JsonReader in, Consumer<Map<?, ?>> row) throws IOException
    {
        in.beginArray();
        while (in.hasNext())
        {
            Object o = value(in);
            if (o instanceof Map)
            {
                row.accept((Map<?, ?>) o);
            }
        }
        in.endArray();
    }

    /** A JSON value: numbers as Double, texts, arrays as lists, objects as maps; null for anything else. */
    private static Object value(JsonReader in) throws IOException
    {
        return value(in, 0);
    }

    /** Nesting deeper than {@link #MAX_DEPTH} is skipped, so no answer can run the stack out. */
    private static Object value(JsonReader in, int depth) throws IOException
    {
        switch (depth > MAX_DEPTH ? JsonToken.NULL : in.peek())
        {
            case NUMBER:
                return in.nextDouble();
            case STRING:
                return in.nextString();
            case BEGIN_ARRAY:
                List<Object> list = new ArrayList<>();
                in.beginArray();
                while (in.hasNext())
                {
                    list.add(value(in, depth + 1));
                }
                in.endArray();
                return list;
            case BEGIN_OBJECT:
                Map<String, Object> map = new HashMap<>();
                in.beginObject();
                while (in.hasNext())
                {
                    map.put(in.nextName(), value(in, depth + 1));
                }
                in.endObject();
                return map;
            default:
                in.skipValue();
                return null;
        }
    }

    private static final int MAX_DEPTH = 32;

    private static Map<?, ?> map(Object e)
    {
        return e instanceof Map ? (Map<?, ?>) e : Collections.emptyMap();
    }

    private static List<?> list(Map<?, ?> o, String key)
    {
        Object e = o.get(key);
        return e instanceof List ? (List<?>) e : Collections.emptyList();
    }

    private static String text(Map<?, ?> o, String key)
    {
        Object e = o.get(key);
        return e instanceof String ? ((String) e).trim() : "";
    }

    private static boolean named(String name)
    {
        return !name.isEmpty() && name.length() <= MAX_NAME;
    }

    /** Texts of a list; none when it is no list of texts. */
    private static String[] texts(Object e)
    {
        List<?> list = e instanceof List ? (List<?>) e : Collections.emptyList();
        return list.stream().allMatch(t -> t instanceof String && named((String) t)) ? list.toArray(new String[0])
            : new String[0];
    }

    /** {@code [x, y, plane, ...]}; null when empty or malformed. */
    private static List<WorldPoint> points(Object e)
    {
        List<?> at = e instanceof List ? (List<?>) e : Collections.emptyList();
        List<WorldPoint> points = new ArrayList<>();
        for (int i = 0; i + 2 < at.size(); i += 3)
        {
            int x = number(at.get(i), 0, MAX_COORDINATE);
            int y = number(at.get(i + 1), 0, MAX_COORDINATE);
            int plane = number(at.get(i + 2), 0, 3);
            if (x == BAD || y == BAD || plane == BAD)
            {
                return null;
            }
            points.add(new WorldPoint(x, y, plane));
        }
        return points.isEmpty() || at.size() % 3 != 0 ? null : points;
    }

    /** A stock: {@link #ENDLESS}, or how many; 0 for anything else. */
    private static int stock(Object e)
    {
        return Double.valueOf(ENDLESS).equals(e) ? ENDLESS : Math.max(0, number(e, 1, MAX_COUNT));
    }

    /** A whole number from {@code min} to {@code max}; {@link #BAD} for anything else. */
    private static int number(Object e, int min, int max)
    {
        double v = e instanceof Double ? (Double) e : Double.NaN;
        return v >= min && v <= max && v == Math.rint(v) ? (int) v : BAD;
    }
}
