package com.hdmapreforged.route;

import lombok.*;
import java.util.*;

/** The jumps starting at each node, as indices into a list of edges; looked up without boxing. Immutable once made. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class EdgeIndex
{
    private final IntMap slots;
    private final int[][] lists;

    /** By where they start (not {@link Edge#ANYWHERE}), each index plus {@code offset}. */
    static EdgeIndex of(List<Edge> edges, int offset)
    {
        IntMap slots = new IntMap(edges.size());
        List<int[]> lists = new ArrayList<>();
        int[] counts = new int[edges.size()];
        for (Edge e : edges)
        {
            if (e.from == Edge.ANYWHERE)
            {
                continue;
            }
            int slot = slots.get(e.from);
            if (slot == IntMap.MISSING)
            {
                slot = lists.size();
                slots.put(e.from, slot);
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
            Edge e = edges.get(i);
            if (e.from != Edge.ANYWHERE)
            {
                int slot = slots.get(e.from);
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
