package com.hdmapreforged;

import static java.lang.Math.*;

import java.util.*;
import java.util.function.*;
import net.runelite.client.party.messages.*;

/**
 * What a party member carries, wears and can do, sent when it changes. Short field names keep the message small;
 * values from other clients are checked before use.
 */
public class HdMapPartyGear extends PartyMemberMessage
{
    static final int INVENTORY = 28;
    static final int EQUIPMENT = 14;
    static final int SKILLS = 24;
    /** Item ids above this are not believed. */
    static final int MAX_ITEM = 100_000;

    /** Inventory ids (-1 empty) and quantities. */
    private final int[] i;
    private final int[] q;
    /** Worn item ids by slot, -1 for none. */
    private final int[] e;
    /** Real and current skill levels, in RuneLite's Skill order. */
    private final int[] s;
    private final int[] b;
    /** Experience per skill; null from older versions. */
    private final int[] x;
    /** Run and special attack energy, 0 to 100; null from older versions. */
    private final Integer r;
    private final Integer p;

    HdMapPartyGear(int[] inventory, int[] quantities, int[] equipment, int[] levels, int[] boosted, int[] experience,
        int run, int special)
    {
        i = inventory;
        q = quantities;
        e = equipment;
        s = levels;
        b = boosted;
        x = experience;
        r = run < 0 ? null : run;
        p = special < 0 ? null : special;
    }

    /** Null when not shared. */
    int[] experience()
    {
        return x == null ? null : copy(x, SKILLS, 0, v -> max(0, min(200_000_000, v)));
    }

    /** 0 to 100, or -1 when not shared. */
    int runEnergy()
    {
        return r == null ? -1 : max(0, min(100, r));
    }

    /** 0 to 100, or -1 when not shared. */
    int specialAttack()
    {
        return p == null ? -1 : max(0, min(100, p));
    }

    int[] inventory()
    {
        return items(i, INVENTORY);
    }

    int[] quantities()
    {
        return copy(q, INVENTORY, 0, v -> max(0, v));
    }

    int[] equipment()
    {
        return items(e, EQUIPMENT);
    }

    int[] levels()
    {
        return levels(s);
    }

    int[] boosted()
    {
        return levels(b);
    }

    private static int[] items(int[] ids, int size)
    {
        return copy(ids, size, -1, id -> id >= 0 && id < MAX_ITEM ? id : -1);
    }

    private static int[] levels(int[] values)
    {
        return copy(values, SKILLS, 0, v -> max(0, min(255, v)));
    }

    /** The first {@code size} values checked, {@code empty} where none came. */
    private static int[] copy(int[] values, int size, int empty, IntUnaryOperator check)
    {
        int[] out = new int[size];
        Arrays.fill(out, empty);
        for (int k = 0; values != null && k < min(values.length, size); k++)
        {
            out[k] = check.applyAsInt(values[k]);
        }
        return out;
    }
}
