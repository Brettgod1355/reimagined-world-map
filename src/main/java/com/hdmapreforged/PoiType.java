package com.hdmapreforged;

import java.awt.*;
import java.util.*;

enum PoiType
{
    TELEPORT("Teleport", Layer.TELEPORTS, 0x9B59FF),
    FAIRY_RING("Fairy ring", Layer.TRANSPORTS, 0x3DDC84),
    SPIRIT_TREE("Spirit tree", Layer.TRANSPORTS, 0x2E9D4A),
    GNOME_GLIDER("Gnome glider", Layer.TRANSPORTS, 0xE0B050),
    BALLOON("Hot air balloon", Layer.TRANSPORTS, 0xE05A47),
    QUETZAL("Quetzal", Layer.TRANSPORTS, 0x3FB6C9),
    MUSHTREE("Magic mushtree", Layer.TRANSPORTS, 0xB070E0),
    OBELISK("Wilderness obelisk", Layer.TRANSPORTS, 0x9AA3AD),
    BOAT("Boat", Layer.TRANSPORTS, 0x4A90E2),
    CHARTER("Charter ship", Layer.TRANSPORTS, 0x2F6FB8),
    CANOE("Canoe", Layer.TRANSPORTS, 0xB07A45),
    CARPET("Magic carpet", Layer.TRANSPORTS, 0xD9534F),
    MINECART("Minecart", Layer.TRANSPORTS, 0x8D99A6),
    PORTAL("Portal", Layer.TRANSPORTS, 0xC070FF),
    LEVER("Lever", Layer.TRANSPORTS, 0xB0B0B0),
    TRANSPORT("Transport", Layer.TRANSPORTS, 0x5DADE2),
    DUNGEON_ENTRANCE("Dungeon entrance", Layer.DUNGEONS, 0xE08A2A),
    MAP_EXIT("Map passage", Layer.DUNGEONS, 0x6FB7E0),
    MOORING("Mooring", Layer.SAILING, 0x2E86C1),
    SALVAGE("Salvage", Layer.SAILING, 0x7D6608),
    RUNECRAFT_ALTAR("Runecrafting altar", Layer.SKILLING, 0xC0392B),
    AGILITY_COURSE("Agility course", Layer.SKILLING, 0x27AE60),
    AGILITY_SHORTCUT("Agility shortcut", Layer.SKILLING, 0x58D68D),
    FARMING_PATCH("Farming patch", Layer.SKILLING, 0x8E6E3B),
    MINIGAME("Minigame", Layer.ACTIVITIES, 0xD35400),
    BANK("Bank", Layer.SERVICES, 0xF1C40F),
    ALTAR("Altar", Layer.SERVICES, 0xE8E8F0),
    ANVIL("Anvil", Layer.SERVICES, 0x95A5A6),
    // The game's own icons: drawn by MapIconLayer from the game's sprites, not as our badges.
    SHOP("Shop", Layer.GAME_ICONS, 0xC8A060),
    QUEST_START("Quest start", Layer.GAME_ICONS, 0x3C8CE6),
    MAP_LINK("Map link", Layer.GAME_ICONS, 0x4AA3E8),
    GAME_ICON("Map icon", Layer.GAME_ICONS, 0xB8B8B8),
    /** A search result without an icon of its own: only selected, never drawn. */
    FOUND("Search result", Layer.GAME_ICONS, 0x46DC5A),
    /** One's grave while it stands: drawn by {@link GraveMarker}. */
    GRAVE("Grave", Layer.GAME_ICONS, 0x8E8E96);

    final String displayName;
    final Layer layer;
    final Color color;

    PoiType(String displayName, Layer layer, int rgb)
    {
        this.displayName = displayName;
        this.layer = layer;
        this.color = new Color(rgb);
    }

    /** Obelisks send you to a random other obelisk unless you can choose. */
    boolean isRandomDestination()
    {
        return this == OBELISK;
    }

    /** Every stop reaches every other. */
    boolean isNetwork()
    {
        return NETWORKS.contains(this);
    }

    /** Fairy rings to obelisks, in the order above. */
    private static final Set<PoiType> NETWORKS = EnumSet.range(FAIRY_RING, OBELISK);
}
