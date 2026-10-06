package com.hdmapreforged;

import com.hdmapreforged.route.*;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import net.runelite.api.coords.*;

/** A map version's map list (our map server's maps.json); its region table decides where bounds overlap. */
final class BaseMaps
{
    private final List<BaseMap> sorted;
    private final Map<Integer, BaseMap> byId = new HashMap<>();
    private RegionTable regions = new RegionTable();
    /** The map version they are of; null when not known. */
    private String version;

    BaseMaps(List<BaseMap> maps)
    {
        List<BaseMap> list = new ArrayList<>(maps);
        // Surface first, then the combined map, then alphabetical.
        list.sort(Comparator.comparingInt((BaseMap m) -> m.id == BaseMap.SURFACE ? 0 : m.id == BaseMap.FULL ? 1 : 2)
            .thenComparing(m -> m.name, String.CASE_INSENSITIVE_ORDER));
        sorted = Collections.unmodifiableList(list);
        for (BaseMap map : list)
        {
            byId.put(map.id, map);
            map.maps = this;
        }
    }

    static final int MAX_COORDINATE = 20_000;
    private static final int MAX_NAME = 100;

    /** Implausible entries are left out, not the whole list; an unreadable list gives no maps. */
    static BaseMaps parse(Gson gson, Reader json)
    {
        JsonArray array;
        try
        {
            array = gson.fromJson(json, JsonArray.class);
        }
        catch (RuntimeException e)
        {
            return new BaseMaps(Collections.emptyList());
        }
        List<BaseMap> maps = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (JsonElement element : array == null ? new JsonArray() : array)
        {
            BaseMap map = map(element);
            if (map != null && ids.add(map.id))
            {
                maps.add(map);
            }
        }
        return new BaseMaps(maps);
    }

    private static BaseMap map(JsonElement element)
    {
        try
        {
            JsonObject o = element.getAsJsonObject();
            JsonArray bounds = o.getAsJsonArray("bounds");
            JsonArray min = bounds.get(0).getAsJsonArray();
            JsonArray max = bounds.get(1).getAsJsonArray();
            JsonArray center = o.has("center") ? o.getAsJsonArray("center") : null;
            int minX = min.get(0).getAsInt();
            int minY = min.get(1).getAsInt();
            int maxX = max.get(0).getAsInt();
            int maxY = max.get(1).getAsInt();
            int id = o.get("mapId").getAsInt();
            String name = o.get("name").getAsString();
            int tiles = o.has("tiles") ? o.get("tiles").getAsInt() : id;
            if (!inWorld(minX) || !inWorld(minY) || !inWorld(maxX) || !inWorld(maxY) || maxX <= minX || maxY <= minY
                || id < BaseMap.FULL || name.trim().isEmpty() || name.length() > MAX_NAME
                || tiles != id && tiles != BaseMap.FULL)
            {
                return null;
            }
            int centerX = center != null ? center.get(0).getAsInt() : (minX + maxX) / 2;
            int centerY = center != null ? center.get(1).getAsInt() : (minY + maxY) / 2;
            if (!inWorld(centerX) || !inWorld(centerY))
            {
                centerX = (minX + maxX) / 2;
                centerY = (minY + maxY) / 2;
            }
            return new BaseMap(id, name, minX, minY, maxX, maxY, centerX, centerY, tiles);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static boolean inWorld(int coordinate)
    {
        return coordinate >= 0 && coordinate <= MAX_COORDINATE;
    }

    boolean isUsable()
    {
        return byId(BaseMap.SURFACE) != null;
    }

    BaseMaps withRegions(RegionTable regions)
    {
        this.regions = regions;
        return this;
    }

    BaseMaps withVersion(String version)
    {
        this.version = version;
        return this;
    }

    String version()
    {
        return version;
    }

    List<BaseMap> all()
    {
        return sorted;
    }

    BaseMap byId(int id)
    {
        return byId.get(id);
    }

    BaseMap surface()
    {
        BaseMap surface = byId.get(BaseMap.SURFACE);
        return surface != null ? surface : sorted.get(0);
    }

    /** One of the maps drawing the point's region (maps may share one), or the map {@link #find} gives. */
    boolean draws(BaseMap map, int x, int y)
    {
        if (map == null || !map.contains(x, y))
        {
            return false;
        }
        int[] owners = drawers(x, y);
        return owners != null && Arrays.stream(owners).anyMatch(id -> id == map.id) || find(x, y) == map;
    }

    /** The owners of the spot's 8×8 zone where known, else the region's owners; null when not known. */
    private int[] drawers(int x, int y)
    {
        int[] zone = regions.zoneOwners(x, y);
        return zone != null ? zone : regions.owners(RegionTable.regionId(x, y));
    }

    /**
     * The map that draws the ground of a game point's floor where the zone's own map does not draw it on that floor (the
     * region table's floor lines), or null.
     */
    BaseMap floorMap(WorldPoint game)
    {
        int[] found = regions.floorOverride(game.getX(), game.getY(), game.getPlane());
        BaseMap map = found == null ? null : byId(found[0]);
        return map != null && (map.id == BaseMap.FULL || map.contains(game.getX(), game.getY())) ? map : null;
    }

    /**
     * The floor {@code on} draws a game point's ground on where the region table says it is not the point's own (the
     * Brimhaven Dungeon's raised paths, walked on floor 2, drawn on floor 1), or -1. Any map drawn with the same tiles
     * draws it there.
     */
    int drawnFloor(BaseMap on, WorldPoint game)
    {
        int[] found = on == null ? null : regions.floorOverride(game.getX(), game.getY(), game.getPlane());
        BaseMap map = found == null ? null : byId(found[0]);
        return map != null && map.tiles == on.tiles ? found[1] : -1;
    }

    /**
     * The game's floor a spot of a map's drawn floor stands for: {@code game} (where the map's moves put it), unless that
     * is no ground ({@code ground}) and a floor the region table draws on {@code drawnFloor} there is: then that floor.
     */
    WorldPoint gameFloor(BaseMap on, WorldPoint game, int drawnFloor, Predicate<WorldPoint> ground)
    {
        if (on == null || ground == null || ground.test(game))
        {
            return game;
        }
        for (int d = 1; d < 4; d++)
        {
            for (int floor : new int[]{game.getPlane() - d, game.getPlane() + d})
            {
                WorldPoint other = floor < 0 || floor > 3 ? null : new WorldPoint(game.getX(), game.getY(), floor);
                if (other != null && drawnFloor(on, other) == drawnFloor && ground.test(other))
                {
                    return other;
                }
            }
        }
        return game;
    }

    /** The map drawing a game point elsewhere when one does (the Kalphite Lair), else {@link #find(int, int)}. */
    BaseMap find(WorldPoint game)
    {
        if (HousePlan.contains(game.getX(), game.getY()))
        {
            // One's house is drawn at the surface map's edge.
            return byId(BaseMap.SURFACE);
        }
        BaseMap floor = floorMap(game);
        if (floor != null)
        {
            return floor;
        }
        WorldMapMoves.Drawn drawn = WorldMapMoves.drawn(game);
        BaseMap moved = drawn == null ? null : byId(drawn.map);
        // Some moves put a place outside the bounds of the map they name.
        if (moved != null && moved.contains(drawn.point.getX(), drawn.point.getY()))
        {
            return moved;
        }
        BaseMap found = find(game.getX(), game.getY());
        if (found != null && WorldMapMoves.covers(found.id, game.getX(), game.getY()))
        {
            // That map shows a moved place here: the smallest other map that draws the spot.
            BaseMap other = null;
            for (BaseMap map : sorted)
            {
                if (map != found && map.id != BaseMap.FULL && map.contains(game.getX(), game.getY())
                    && !WorldMapMoves.covers(map.id, game.getX(), game.getY())
                    && (other == null || map.area() < other.area()))
                {
                    other = map;
                }
            }
            return other != null ? other : byId(BaseMap.FULL);
        }
        return found;
    }

    /** Whether a map shows this game point where it is, not a moved place instead. */
    boolean drawsGame(BaseMap map, WorldPoint game)
    {
        if (map != null && regions.floorOverride(game.getX(), game.getY(), game.getPlane()) != null)
        {
            // Its floor's ground is drawn by the map the table names (and every map with the same tiles).
            return map.contains(game.getX(), game.getY()) && drawnFloor(map, game) >= 0;
        }
        return map != null && draws(map, game.getX(), game.getY())
            && !WorldMapMoves.covers(map.id, game.getX(), game.getY());
    }

    /**
     * The smallest map drawing the point's region; the map of everything where the region has none; else the smallest
     * map containing the point, and the map of everything last. Null when no map holds it.
     */
    BaseMap find(int x, int y)
    {
        BaseMap full = byId.get(BaseMap.FULL);
        boolean fullDraws = full != null && full.contains(x, y);
        int[] owners = drawers(x, y);
        BaseMap best = null;
        if (owners != null)
        {
            for (int id : owners)
            {
                BaseMap map = byId.get(id);
                if (map != null && map.contains(x, y) && (best == null || map.area() < best.area()))
                {
                    best = map;
                }
            }
            if (best != null || owners.length == 0 && fullDraws)
            {
                return best != null ? best : full;
            }
        }
        for (BaseMap map : sorted)
        {
            if (map.id != BaseMap.FULL && map.contains(x, y) && (best == null || map.area() < best.area()))
            {
                best = map;
            }
        }
        // The map of everything draws all of the game's ground: where no other map does, it shows the spot.
        return best != null ? best : fullDraws ? full : null;
    }
}
