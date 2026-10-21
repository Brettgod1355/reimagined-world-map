package com.hdmapreforged;

import java.awt.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

enum PoiType
{
    TELEPORT, FAIRY_RING, SPIRIT_TREE, GNOME_GLIDER, BALLOON, QUETZAL, MUSHTREE, OBELISK, BOAT, CHARTER, CANOE, CARPET,
    MINECART, PORTAL, LEVER, TRANSPORT, DUNGEON_ENTRANCE, MAP_EXIT, MOORING, SALVAGE, RUNECRAFT_ALTAR, AGILITY_COURSE,
    AGILITY_SHORTCUT, FARMING_PATCH, MINIGAME, BANK, ALTAR, ANVIL,
    // The game's own icons: drawn by MapIconLayer from the game's sprites, not as our badges.
    SHOP, QUEST_START, MAP_LINK, GAME_ICON,
    /** A search result without an icon of its own: only selected, never drawn. */
    FOUND,
    /** One's grave while it stands: drawn by {@link GraveMarker}. */
    GRAVE;

    final String displayName;
    final Layer layer;
    final Color color;
    /** The game map element whose icon stands for this type (GameIconSprites); -1 none. */
    final int element;

    PoiType()
    {
        String[] cells = Rows.BY_TYPE.get(name());
        displayName = cells[1];
        layer = Layer.valueOf(cells[2]);
        color = new Color(Integer.parseInt(cells[3], 16));
        element = cells.length > 4 ? Integer.parseInt(cells[4]) : -1;
    }

    /** The types' names, layers, colours and game icons, bundled (data/poi_types.tsv). */
    private static final class Rows
    {
        static final Map<String, String[]> BY_TYPE = new HashMap<>();

        static
        {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                PoiType.class.getResourceAsStream("data/poi_types.tsv"), StandardCharsets.UTF_8)))
            {
                reader.lines().filter(line -> !line.startsWith("#")).map(line -> line.split("\t"))
                    .forEach(cells -> BY_TYPE.put(cells[0], cells));
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Obelisks send you to a random other obelisk unless you can choose. */
    boolean isRandomDestination()
    {
        return this == OBELISK;
    }

    /** Fairy rings to obelisks, in the order above; every stop of one reaches every other. */
    static final Set<PoiType> NETWORKS = EnumSet.range(FAIRY_RING, OBELISK);
}
