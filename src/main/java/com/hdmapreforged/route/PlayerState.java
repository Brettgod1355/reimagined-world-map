package com.hdmapreforged.route;

import java.util.*;
import lombok.*;

/** What a route may use, taken on the client thread and read elsewhere. Immutable. */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlayerState
{
    public static final PlayerState UNKNOWN = new PlayerState(ItemSnapshot.NONE, 1, false, new int[0], true, false, -1,
        false, Collections.emptySet(), true);

    @With
    public final ItemSnapshot items;
    @With
    public final int sailingLevel;
    /** Pandemonium done: the player can sail their own boat. */
    @With
    public final boolean sailing;
    public final int[] boats;
    /** Per boat of {@link #boats}: its teleport focus, 0 none, 1 (Summon Boat), 2 greater (also Teleport to Boat). */
    public final int[] boatFocus;
    public final boolean running;
    @With
    public final boolean inHouse;
    /** In a house: where its portal leads out; outside: one's own house portal. -1 when not known. */
    public final int houseExit;
    @With
    public final boolean ownHouse;
    public final Set<String> houseFeatures;
    /** House teleports land inside (the house option Teleport Inside, as it is at first); else at the house portal. */
    public final boolean landsInside;
    /**
     * In an area whose way out leads back to where one came from (ReturnAreas: the Rune essence mine): where that is;
     * else -1.
     */
    @With
    public final int returnExit;
    /** With the bank's items too ({@link #withBank}): what is carried without them; else null. */
    @With
    public final ItemSnapshot carried;

    public PlayerState(ItemSnapshot items, int sailingLevel, boolean sailing, int[] boats, boolean running, boolean inHouse,
        int houseExit, boolean ownHouse, Set<String> houseFeatures, boolean landsInside)
    {
        this(items, sailingLevel, sailing, boats.clone(), new int[boats.length], running, inHouse, houseExit, ownHouse,
            Collections.unmodifiableSet(new LinkedHashSet<>(houseFeatures)), landsInside, -1, null);
    }

    /** The same, with other items or Sailing. */
    public PlayerState with(ItemSnapshot items, int sailingLevel, boolean sailing)
    {
        return withItems(items).withSailingLevel(sailingLevel).withSailing(sailing);
    }

    /** The same with the bank's items too, keeping what is carried apart (to say what to take out). */
    public PlayerState withBank(Map<Integer, Long> bank)
    {
        return withItems(items.plus(bank)).withCarried(items);
    }

    /**
     * The same in one's own house ({@code inside}: a route starting on its plan), or out of any house (a route's later
     * part, on from a bank, starts outside).
     */
    public PlayerState inOwnHouse(boolean inside)
    {
        return withInHouse(inside).withOwnHouse(inside);
    }

    /** The same, in an area whose way out leads back to {@code exit} (or -1: none known). */
    public PlayerState withReturn(int exit)
    {
        return withReturnExit(exit);
    }

    /** The same, with each boat's teleport focus (see {@link #boatFocus}). */
    public PlayerState withBoatFocus(int[] focus)
    {
        return new PlayerState(items, sailingLevel, sailing, boats, Arrays.copyOf(focus, boats.length), running, inHouse,
            houseExit, ownHouse, houseFeatures, landsInside, returnExit, carried);
    }
}
