package com.hdmapreforged;

import java.awt.*;
import java.util.*;
import java.util.List;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.events.*;

/**
 * The route's ground tiles via HD World Markers' public plugin messages ({@code tiles} and {@code clear}, one owner;
 * its guide's "For plugin developers"). It clears them on logout, world hop, connection loss and a profile change: they
 * are sent again after.
 */
final class TileMarkersBridge
{
    static final String NAMESPACE = "hd-world-markers";
    static final String OWNER = "hd-map-reforged-route";
    static final String PLUGIN_NAME = "HD World Markers";
    /** It takes at most this many tiles from one sender. */
    static final int MAX_TILES = 1000;

    private TileMarkersBridge()
    {
    }

    /** A tile to mark, optionally labelled. */
    @AllArgsConstructor
    static final class Tile
    {
        final WorldPoint point;
        final Color color;
        /** Null: a faint fill after the colour's transparency. */
        final Color fill;
        final int width;
        final String label;

        Tile(WorldPoint point, Color color, String label)
        {
            this(point, color, null, 2, label);
        }
    }

    static PluginMessage tiles(List<Tile> tiles)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tile tile : tiles)
        {
            if (list.size() >= MAX_TILES)
            {
                break;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("point", tile.point);
            entry.put("color", tile.color);
            // Follows the colour's transparency, so fading tiles fade as a whole.
            entry.put("fill", tile.fill != null ? tile.fill : new Color(tile.color.getRed(), tile.color.getGreen(),
                tile.color.getBlue(), tile.color.getAlpha() * 50 / 255));
            entry.put("width", tile.width);
            if (tile.label != null)
            {
                entry.put("label", tile.label);
            }
            list.add(entry);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("owner", OWNER);
        data.put("tiles", list);
        return new PluginMessage(NAMESPACE, "tiles", data);
    }

    static PluginMessage clear()
    {
        Map<String, Object> data = new HashMap<>();
        data.put("owner", OWNER);
        return new PluginMessage(NAMESPACE, "clear", data);
    }
}
