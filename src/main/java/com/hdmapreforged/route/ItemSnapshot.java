package com.hdmapreforged.route;

import lombok.*;
import java.util.*;
import java.util.regex.*;

/**
 * Items carried and runes a worn staff gives without limit: what a route may use. The bank only through a
 * {@link BankDetour} (setting), as {@link #plus}. Immutable.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ItemSnapshot
{
    static final Map<Integer, int[]> COMBINATIONS = new HashMap<>();
    /** Compiled once: a split on a pattern compiles it on every call, and requests check hundreds of columns. */
    public static final Pattern ALL = Pattern.compile("&&");
    public static final Pattern ANY = Pattern.compile("\\|\\|");
    public static final int AIR = 556;
    public static final int WATER = 555;
    public static final int EARTH = 557;
    public static final int FIRE = 554;

    static
    {
        COMBINATIONS.put(4694, new int[]{WATER, FIRE});
        COMBINATIONS.put(4695, new int[]{AIR, WATER});
        COMBINATIONS.put(4696, new int[]{AIR, EARTH});
        COMBINATIONS.put(4697, new int[]{AIR, FIRE});
        COMBINATIONS.put(4698, new int[]{WATER, EARTH});
        COMBINATIONS.put(4699, new int[]{EARTH, FIRE});
    }

    public static final ItemSnapshot NONE = new ItemSnapshot(Collections.emptyMap(), Collections.emptySet());
    public static final ItemSnapshot EVERYTHING = new ItemSnapshot(Collections.emptyMap(), Collections.emptySet());

    final Map<Integer, Long> carried;
    final Set<Integer> unlimited;

    /** Whether {@code other} carries the same (an inventory that changed back and forth is no change). */
    public boolean sameAs(ItemSnapshot other)
    {
        return other != null && carried.equals(other.carried);
    }

    /** Whether {@code other} carries the same of these items (and the same staff's runes). */
    public boolean sameFor(ItemSnapshot other, Set<Integer> ids)
    {
        if (other == null || !unlimited.equals(other.unlimited))
        {
            return false;
        }
        for (int id : ids)
        {
            if (!carried.getOrDefault(id, 0L).equals(other.carried.getOrDefault(id, 0L)))
            {
                return false;
            }
        }
        return true;
    }

    public static ItemSnapshot of(Map<Integer, Long> carried, Set<Integer> unlimited)
    {
        var all = new HashMap<Integer, Long>();
        carried.forEach((id, n) -> add(all, id, n));
        return new ItemSnapshot(Collections.unmodifiableMap(all), Collections.unmodifiableSet(new HashSet<>(unlimited)));
    }

    /** These items and {@code more} (a bank's), with the same staff's runes. */
    public ItemSnapshot plus(Map<Integer, Long> more)
    {
        var all = new HashMap<Integer, Long>(carried);
        more.forEach((id, n) -> add(all, id, n));
        return new ItemSnapshot(Collections.unmodifiableMap(all), unlimited);
    }

    static void add(Map<Integer, Long> into, int id, long quantity)
    {
        into.merge(id, quantity, Long::sum);
        var parts = COMBINATIONS.get(id);
        if (parts != null)
        {
            for (int part : parts)
            {
                into.merge(part, quantity, Long::sum);
            }
        }
    }

    public static Set<Integer> runesFromWeapon(String name)
    {
        var runes = new HashSet<Integer>();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!n.contains("staff") && !n.contains("tome of") && !n.contains("wand") || n.contains("(empty)")
            || n.contains("uncharged"))
        {
            return runes;
        }
        // The words that give each rune.
        String[] words = {"air smoke mist dust", "water mud steam mist kodai", "earth lava mud dust", "fire lava steam smoke"};
        int[] ids = {AIR, WATER, EARTH, FIRE};
        for (int i = 0; i < ids.length; i++)
        {
            if (Arrays.stream(words[i].split(" ")).anyMatch(n::contains))
            {
                runes.add(ids[i]);
            }
        }
        return runes;
    }

    /**
     * Whether what is carried meets a column like {@code "556=3&&563=1"}. A token that is no item id counts as met:
     * only a certainly missing item rules out.
     */
    public boolean has(String column)
    {
        if (this == EVERYTHING || column == null || column.trim().isEmpty())
        {
            return true;
        }
        for (String all : ALL.split(column))
        {
            boolean any = false;
            boolean judged = false;
            for (String alternative : ANY.split(all))
            {
                var parts = alternative.trim().split("=");
                String token = parts[0].trim();
                if (token.isEmpty())
                {
                    continue;
                }
                long quantity = 1;
                if (parts.length > 1)
                {
                    try
                    {
                        quantity = Long.parseLong(parts[1].trim());
                    }
                    catch (NumberFormatException e)
                    {
                        // One then.
                    }
                }
                Integer id = token.length() <= 9 && token.chars().allMatch(Character::isDigit) ? Integer.valueOf(token)
                    : null;
                if (id == null)
                {
                    any = true;
                    continue;
                }
                judged = true;
                if (unlimited.contains(id) || carried.getOrDefault(id, 0L) >= quantity)
                {
                    any = true;
                }
            }
            if (judged && !any)
            {
                return false;
            }
        }
        return true;
    }
}
