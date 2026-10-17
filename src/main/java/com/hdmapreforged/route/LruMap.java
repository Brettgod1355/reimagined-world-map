package com.hdmapreforged.route;

import java.util.*;

/** A map in access order (load factor 0.75) that keeps only the {@code limit} entries used last. */
public final class LruMap<K, V> extends LinkedHashMap<K, V>
{
    final int limit;

    public LruMap(int capacity, int limit)
    {
        super(capacity, 0.75f, true);
        this.limit = limit;
    }

    protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
    {
        return size() > limit;
    }
}
