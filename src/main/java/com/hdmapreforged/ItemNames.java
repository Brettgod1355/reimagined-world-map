package com.hdmapreforged;

import com.hdmapreforged.route.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.client.callback.*;
import net.runelite.client.game.*;
import net.runelite.client.util.*;

/**
 * Item names for requirement lists, and the Grand Exchange prices the client knows; looked up on the client thread,
 * read from Swing.
 */
@RequiredArgsConstructor
final class ItemNames implements IntFunction<String>, SearchResults.Prices
{
    private final ClientThread clientThread;
    private final ItemManager itemManager;
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    /** Id to last lookup time; retried later, as the game's item data may not have been ready. */
    private final Map<Integer, Long> unnamed = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 30_000;
    private final Map<Integer, Integer> prices = new ConcurrentHashMap<>();
    /** Id to when its price was looked up; prices move, so they are looked up again after a while. */
    private final Map<Integer, Long> priced = new ConcurrentHashMap<>();
    private static final long PRICE_MS = 10 * 60_000;
    /** More than a big shop sells; the game's own image cache keeps only 128 for every plugin. */
    static final int IMAGES = 512;
    /** The pictures of the items shown last, so a list built again never asks the game again. Swing thread. */
    private final Map<Integer, AsyncBufferedImage> images = new LruMap<>(16, IMAGES);

    @Override
    public int price(int id)
    {
        Integer price = prices.get(id);
        return price == null ? -1 : price;
    }

    /** Shows the game's picture of an item in {@code label}, once loaded. */
    void picture(int id, JLabel label)
    {
        images.computeIfAbsent(id, itemManager::getImage).addTo(label);
    }

    @Override
    public String apply(int id)
    {
        String name = names.get(id);
        return name != null || !unnamed.containsKey(id) ? name : "Item " + id;
    }

    /** Client thread. */
    private String lookUp(int id)
    {
        try
        {
            ItemComposition item = itemManager.getItemComposition(id);
            String name = item == null ? null : item.getName();
            return name == null || name.isEmpty() || "null".equals(name) ? null : name;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private boolean wanted(int id, long now)
    {
        if (!names.containsKey(id))
        {
            Long tried = unnamed.get(id);
            if (tried == null || now - tried >= RETRY_MS)
            {
                return true;
            }
        }
        Long priced = this.priced.get(id);
        return priced == null || now - priced >= PRICE_MS;
    }

    /** Client thread. */
    private int lookUpPrice(int id)
    {
        try
        {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, itemManager.getItemPrice(id)));
        }
        catch (RuntimeException e)
        {
            return 0;
        }
    }

    @Override
    public void resolve(Collection<Integer> ids, Runnable done)
    {
        // Previews run without a client.
        long now = System.currentTimeMillis();
        if (clientThread == null || ids.stream().noneMatch(id -> wanted(id, now)))
        {
            return;
        }
        clientThread.invoke(() -> {
            long at = System.currentTimeMillis();
            for (int id : ids)
            {
                if (!wanted(id, at))
                {
                    continue;
                }
                if (!names.containsKey(id))
                {
                    String name = lookUp(id);
                    if (name != null)
                    {
                        names.put(id, name);
                        unnamed.remove(id);
                    }
                    else
                    {
                        unnamed.put(id, at);
                    }
                }
                prices.put(id, lookUpPrice(id));
                priced.put(id, at);
            }
            SwingUtilities.invokeLater(done);
        });
    }
}
