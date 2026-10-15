package com.hdmapreforged;

import java.util.*;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.events.*;

/** Talking to the Shortest Path plugin through RuneLite plugin messages (its public message format only). */
final class ShortestPathBridge
{
    static final String NAMESPACE = "shortestpath";
    static final String PATH = "path";
    static final String CLEAR = "clear";
    static final String TARGET = "target";
    /** Plugins that answer this namespace: Shortest Path, and GPS (its own name, Shortest Path's messages). */
    static final List<String> PLUGIN_NAMES = List.of("Shortest Path", "GPS");
    /** Cap per message: messages come from other plugins. */
    static final int MAX_POINTS = 64;

    ShortestPathBridge()
    {
    }

    static PluginMessage path(WorldPoint target)
    {
        return path(target, Map.of());
    }

    /**
     * Asks it to tell which transports its path uses, so we can draw the route too (it keeps that until a clear);
     * {@code target} null keeps its route. {@code config}: overrides another plugin gave, kept.
     */
    static PluginMessage path(WorldPoint target, Map<?, ?> config)
    {
        var data = new HashMap<String, Object>();
        if (target != null)
        {
            data.put(TARGET, target);
        }
        var wanted = new HashMap<Object, Object>(config);
        wanted.put("postTransports", true);
        data.put("config", wanted);
        return new PluginMessage(NAMESPACE, PATH, data);
    }

    /** The config overrides a path message carries; empty when none. */
    static Map<?, ?> config(PluginMessage message)
    {
        Object config = message.getData() == null ? null : message.getData().get("config");
        return config instanceof Map<?, ?> ? (Map<?, ?>) config : Map.of();
    }

    static final String TRANSPORTS = "transports";

    /** A transport or teleport of Shortest Path's route. */
    @RequiredArgsConstructor
    static final class Jump
    {
        final WorldPoint from;
        final WorldPoint to;
        final String name;
    }

    static boolean isTransports(PluginMessage message)
    {
        return is(message, TRANSPORTS);
    }

    static boolean is(PluginMessage message, String name)
    {
        return NAMESPACE.equals(message.getNamespace()) && name.equals(message.getName());
    }

    /** In order; empty when unreadable. */
    static List<Jump> jumps(PluginMessage message)
    {
        var data = message.getData();
        if (data == null || !(data.get("origin") instanceof List<?>) || !(data.get("destination") instanceof List<?>))
        {
            return Collections.emptyList();
        }
        var origins = (List<?>) data.get("origin");
        var destinations = (List<?>) data.get("destination");
        var names = data.get("displayInfo") instanceof List<?> ? (List<?>) data.get("displayInfo") : Collections.emptyList();
        var jumps = new ArrayList<Jump>();
        for (int i = 0; i < origins.size() && i < destinations.size() && jumps.size() < MAX_POINTS; i++)
        {
            var from = point(origins.get(i));
            var to = point(destinations.get(i));
            if (from == null || to == null)
            {
                continue;
            }
            Object name = i < names.size() ? names.get(i) : null;
            jumps.add(new Jump(from, to, name instanceof String && !((String) name).trim().isEmpty() ? ((String) name).trim()
                : "Transport"));
        }
        return jumps;
    }

    static PluginMessage clear()
    {
        return new PluginMessage(NAMESPACE, CLEAR);
    }

    static boolean isPath(PluginMessage message)
    {
        return is(message, PATH);
    }

    static boolean isClear(PluginMessage message)
    {
        return is(message, CLEAR);
    }

    /** A WorldPoint, a packed point, or a collection of either; empty when none or unreadable. */
    static List<WorldPoint> targets(PluginMessage message)
    {
        var data = message.getData();
        Object target = data == null ? null : data.get(TARGET);
        var points = new ArrayList<WorldPoint>();
        for (Object one : target instanceof Collection<?> ? (Collection<?>) target : Collections.singletonList(target))
        {
            if (points.size() >= MAX_POINTS)
            {
                break;
            }
            var point = point(one);
            if (point != null)
            {
                points.add(point);
            }
        }
        return points.isEmpty() ? Collections.emptyList() : points;
    }

    /** A WorldPoint, or packed as x | y << 15 | plane << 30 (-1 none); null outside the game world. */
    static WorldPoint point(Object value)
    {
        WorldPoint point = null;
        if (value instanceof WorldPoint)
        {
            point = (WorldPoint) value;
        }
        else if (value instanceof Integer)
        {
            int packed = (Integer) value;
            if (packed == -1)
            {
                return null;
            }
            point = new WorldPoint(packed & 0x7FFF, (packed >>> 15) & 0x7FFF, (packed >>> 30) & 0x3);
        }
        return HdMapPartyLocation.plausible(point) ? point : null;
    }

    /** The target nearest the player (the first when unknown). */
    static WorldPoint nearest(List<WorldPoint> targets, WorldPoint from)
    {
        WorldPoint best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (var target : targets)
        {
            int d = from == null ? 0 : target.getPlane() == from.getPlane() ? target.distanceTo2D(from)
                : target.distanceTo2D(from) + 1000;
            if (best == null || d < bestDistance)
            {
                best = target;
                bestDistance = d;
            }
        }
        return best;
    }
}
