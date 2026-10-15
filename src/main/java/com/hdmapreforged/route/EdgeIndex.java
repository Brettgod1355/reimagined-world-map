package com.hdmapreforged.route;

import lombok.*;
import java.util.*;

/**
 * The jumps starting (or ending) at each node, as indices into a list of edges; looked up without boxing. Immutable
 * once made.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class EdgeIndex
{
    final IntMap slots;
    final int[][] lists;

    /** By where they start (not {@link Edge#ANYWHERE}), each index plus {@code offset}. */
    static EdgeIndex of(List<Edge> edges, int offset)
    {
        return of(edges, offset, false);
    }

    /** By where they end, each index plus {@code offset}: a flood back from a target. */
    static EdgeIndex byDestination(List<Edge> edges, int offset)
    {
        return of(edges, offset, true);
    }

    static EdgeIndex of(List<Edge> edges, int offset, boolean byDestination)
    {
        var slots = new IntMap(edges.size());
        var lists = new ArrayList<int[]>();
        int[] counts = new int[edges.size()];
        for (Edge e : edges)
        {
            int key = byDestination ? e.to : e.from;
            if (key == Edge.ANYWHERE)
            {
                continue;
            }
            int slot = slots.get(key);
            if (slot == IntMap.MISSING)
            {
                slot = lists.size();
                slots.put(key, slot);
                lists.add(null);
            }
            counts[slot]++;
        }
        int[][] made = new int[lists.size()][];
        for (int i = 0; i < made.length; i++)
        {
            made[i] = new int[counts[i]];
            counts[i] = 0;
        }
        for (int i = 0; i < edges.size(); i++)
        {
            int key = byDestination ? edges.get(i).to : edges.get(i).from;
            if (key != Edge.ANYWHERE)
            {
                int slot = slots.get(key);
                made[slot][counts[slot]++] = offset + i;
            }
        }
        return new EdgeIndex(slots, made);
    }

    int[] get(int node)
    {
        int slot = slots.get(node);
        return slot == IntMap.MISSING ? null : lists[slot];
    }
}
