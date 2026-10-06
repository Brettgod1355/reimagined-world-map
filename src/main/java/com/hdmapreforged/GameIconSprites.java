package com.hdmapreforged;

import java.awt.image.*;
import java.util.*;
import java.util.concurrent.*;
import net.runelite.api.*;
import net.runelite.api.worldmap.*;

/** The game's own world map icons, read from the client: drawn as the game's icons, and so our icons look like them. */
final class GameIconSprites
{
    /** The game map elements whose icons stand for our types. */
    private static final Map<PoiType, Integer> ELEMENTS = new EnumMap<>(PoiType.class);

    static
    {
        ELEMENTS.put(PoiType.BANK, 5);
        ELEMENTS.put(PoiType.ANVIL, 10);
        ELEMENTS.put(PoiType.DUNGEON_ENTRANCE, 12);
        ELEMENTS.put(PoiType.MAP_EXIT, 13);
        ELEMENTS.put(PoiType.MAP_LINK, 13);
        ELEMENTS.put(PoiType.ALTAR, 21);
        ELEMENTS.put(PoiType.MINIGAME, 40);
        ELEMENTS.put(PoiType.AGILITY_COURSE, 51);
        ELEMENTS.put(PoiType.FARMING_PATCH, 55);
        ELEMENTS.put(PoiType.AGILITY_SHORTCUT, 71);
        ELEMENTS.put(PoiType.FAIRY_RING, 965);
        ELEMENTS.put(PoiType.SALVAGE, 1050);
        ELEMENTS.put(PoiType.MOORING, 1055);
    }

    private static final Map<PoiType, BufferedImage> SPRITES = new ConcurrentHashMap<>();
    private static final Map<Integer, BufferedImage> ELEMENT_SPRITES = new ConcurrentHashMap<>();

    static boolean hasElements()
    {
        return !ELEMENT_SPRITES.isEmpty();
    }

    static BufferedImage element(int id)
    {
        return id < 0 ? null : ELEMENT_SPRITES.get(id);
    }

    /** Map elements read per call (each client tick): all of them, hundreds, in one tick made a hitch. */
    static final int SLICE = 50;

    /**
     * Client thread only: reads up to {@link #SLICE} of {@code ids}, taking them off it; false until none are left, or
     * while the game has not loaded its data yet.
     */
    static boolean loadElements(Client client, Collection<Integer> ids)
    {
        Iterator<Integer> left = ids.iterator();
        for (int n = 0; n < SLICE && left.hasNext(); n++)
        {
            int id = left.next();
            if (!ELEMENT_SPRITES.containsKey(id))
            {
                MapElementConfig config = config(client, id);
                if (config == null && waiting(client))
                {
                    return false;
                }
                BufferedImage image = config == null ? null : icon(config);
                if (image != null)
                {
                    ELEMENT_SPRITES.put(id, image);
                }
            }
            left.remove();
        }
        return ids.isEmpty();
    }

    /** Null for an id the game does not know (or not yet). */
    private static MapElementConfig config(Client client, int id)
    {
        try
        {
            return client.getMapElementConfig(id);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Still null once logged in: an id gone after a game update, not worth retrying every tick. */
    private static boolean waiting(Client client)
    {
        return client.getGameState() != GameState.LOGGED_IN;
    }

    private static BufferedImage icon(MapElementConfig config)
    {
        SpritePixels pixels = config.getMapIcon(false);
        BufferedImage image = pixels == null ? null : pixels.toBufferedImage();
        return image != null && image.getWidth() > 0 && image.getHeight() > 0 ? image : null;
    }

    private GameIconSprites()
    {
    }

    /** Null when not loaded yet, or a type we draw ourselves. */
    static BufferedImage get(PoiType type)
    {
        return SPRITES.get(type);
    }

    static void clear()
    {
        SPRITES.clear();
        ELEMENT_SPRITES.clear();
    }

    /** Client thread only; false while the game has not loaded its data yet. */
    static boolean load(Client client)
    {
        for (Map.Entry<PoiType, Integer> entry : ELEMENTS.entrySet())
        {
            MapElementConfig config = config(client, entry.getValue());
            if (config == null)
            {
                if (waiting(client))
                {
                    return false;
                }
                continue;
            }
            BufferedImage image = icon(config);
            if (image != null)
            {
                SPRITES.put(entry.getKey(), image);
            }
        }
        return true;
    }
}
