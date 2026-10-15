package com.hdmapreforged;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * Where the game's world map draws part of the game elsewhere (the Kalphite Lair beside the desert caves,
 * floor 2 as floor 0): turns drawn coordinates into the game's own. Also where it draws the floors above a part
 * flat on the ground floor (Keldagrim's houses). From {@code data/world_map_moves.tsv}.
 */
@lombok.extern.slf4j.Slf4j
final class WorldMapMoves
{
    @RequiredArgsConstructor
    static final class Move
    {
        final int map;
        final int x;
        final int y;
        final int width;
        final int height;
        final int dx;
        final int dy;
        final int plane;
        final int planes;
    }

    /** The parts drawn elsewhere, in the file's order. */
    static final List<Move> MOVES = new ArrayList<>();
    /** Every part listed, the ones drawn where they are with floors drawn flat too: for {@link #floorsDrawnFlat}. */
    static final List<Move> ALL = load();

    static
    {
        for (Move move : ALL)
        {
            if (move.dx != 0 || move.dy != 0 || move.plane != 0)
            {
                MOVES.add(move);
            }
        }
    }

    WorldMapMoves()
    {
    }

    /** The last move that fits: whole map squares are listed before zones, so a zone's (finer) wins, as in the game. */
    static Move last(Predicate<Move> fits)
    {
        return last(MOVES, fits);
    }

    static Move last(List<Move> moves, Predicate<Move> fits)
    {
        for (int i = moves.size() - 1; i >= 0; i--)
        {
            Move move = moves.get(i);
            if (fits.test(move))
            {
                return move;
            }
        }
        return null;
    }

    static WorldPoint toWorld(int mapId, WorldPoint drawn)
    {
        Move found = last(move -> move.map == mapId && inside(move, drawn.getX(), drawn.getY()));
        if (found == null)
        {
            return drawn;
        }
        int plane = found.plane + Math.min(drawn.getPlane(), found.planes - 1);
        return new WorldPoint(drawn.getX() + found.dx, drawn.getY() + found.dy, Math.min(3, plane));
    }

    /** As the map of everything draws them: a moved part is drawn where its own map draws it. */
    static WorldPoint fromDrawnAnywhere(WorldPoint drawn)
    {
        Move found = last(move -> inside(move, drawn.getX(), drawn.getY()));
        return found == null ? drawn : toWorld(found.map, drawn);
    }

    /** Whether map {@code mapId} draws a moved part of the game at this spot (the Dagannoth Kings' lair). */
    static boolean covers(int mapId, int x, int y)
    {
        return last(move -> move.map == mapId && inside(move, x, y)) != null;
    }

    @RequiredArgsConstructor
    static final class Drawn
    {
        final int map;
        final WorldPoint point;
    }

    static WorldPoint toDrawn(int mapId, WorldPoint game)
    {
        Move found = last(move -> move.map == mapId && moves(move, game));
        return found == null ? null : drawnBy(found, game);
    }

    static Drawn drawn(WorldPoint game)
    {
        Move found = last(move -> moves(move, game));
        return found == null ? null : new Drawn(found.map, drawnBy(found, game));
    }

    static boolean moves(Move move, WorldPoint game)
    {
        int floor = game.getPlane() - move.plane;
        return inside(move, game.getX() - move.dx, game.getY() - move.dy) && floor >= 0 && floor < move.planes;
    }

    static boolean inside(Move move, int x, int y)
    {
        return x >= move.x && x < move.x + move.width && y >= move.y && y < move.y + move.height;
    }

    static WorldPoint drawnBy(Move move, WorldPoint game)
    {
        return new WorldPoint(game.getX() - move.dx, game.getY() - move.dy, game.getPlane() - move.plane);
    }

    /**
     * How many floors a map draws flat on a drawn spot, as the game's own map does (Keldagrim's houses, the God Wars
     * boss rooms' three floors): 1 where only the floor drawn there shows, every other floor there being another place.
     * The map of everything draws each part as the part's own map does: ask with that map's id.
     */
    static int floorsDrawnFlat(int mapId, int x, int y)
    {
        Move found = last(ALL, move -> move.map == mapId && inside(move, x, y));
        return found == null ? 1 : found.planes;
    }

    static List<Move> load()
    {
        var moves = new ArrayList<Move>();
        InputStream in = WorldMapMoves.class.getResourceAsStream("data/world_map_moves.tsv");
        if (in == null)
        {
            log.warn("world_map_moves.tsv is missing");
            return moves;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("#") || line.trim().isEmpty())
                {
                    continue;
                }
                try
                {
                    // Map id, x y width height, dx dy floor [floors].
                    var v = Arrays.stream(line.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
                    moves.add(new Move(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v.length > 8 ? v[8] : 1));
                }
                catch (RuntimeException e)
                {
                    log.warn("Skipping unreadable line of world_map_moves.tsv: {}", line);
                }
            }
        }
        catch (IOException e)
        {
            log.warn("Could not read world_map_moves.tsv", e);
        }
        return moves;
    }
}
