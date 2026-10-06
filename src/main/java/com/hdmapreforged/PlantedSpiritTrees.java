package com.hdmapreforged;

import java.util.*;
import java.util.function.*;
import net.runelite.api.gameval.*;

/**
 * The spirit trees one plants (Port Sarim, Etceteria, Brimhaven, Hosidius, the Farming Guild) that stand grown for this
 * account. The game says so only near the patch: its farming varbit, read where RuneLite's Time Tracking reads it
 * (FarmingWorld: the patch's regions and varbit), is 20 for a grown tree one travels by ("Spirit Tree[Travel,...]",
 * PatchImplementation.SPIRIT_TREE); any other value (growing, diseased, dead, to check, weeds) is none. Kept per account
 * (RS profile key {@link #KEY}); a patch not visited since is not known, so not used.
 */
final class PlantedSpiritTrees
{
    static final String KEY = "plantedSpiritTrees";
    /** The patch state of a grown spirit tree one can travel by. */
    static final int GROWN = 20;
    /** As the travel table's stops are named; their regions; the patch's varbit. */
    private static final Object[][] PATCHES = {
        {"Port Sarim", new int[]{12082}, VarbitID.FARMING_TRANSMIT_A},
        {"Etceteria", new int[]{10300}, VarbitID.FARMING_TRANSMIT_B},
        {"Brimhaven", new int[]{11058, 11057}, VarbitID.FARMING_TRANSMIT_B},
        {"Hosidius", new int[]{6967, 6711}, VarbitID.FARMING_TRANSMIT_F},
        {"Farming Guild", new int[]{4922}, VarbitID.FARMING_TRANSMIT_A},
    };

    private volatile Set<String> grown = Collections.emptySet();

    /** A stop of the travel table that needs a tree planted ("Only after planting a spirit tree here"). */
    static boolean planted(Poi stop)
    {
        return stop.type == PoiType.SPIRIT_TREE && stop.note != null && stop.note.startsWith("Only after planting");
    }

    Set<String> grown()
    {
        return grown;
    }

    void load(String saved)
    {
        grown = saved == null || saved.isBlank() ? Collections.emptySet()
            : Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(saved.split(","))));
    }

    String text()
    {
        return String.join(",", grown);
    }

    /** In {@code region}: reads its patch, if it has one. Whether what is known changed. Client thread. */
    boolean update(int region, IntUnaryOperator varbit)
    {
        for (Object[] patch : PATCHES)
        {
            for (int id : (int[]) patch[1])
            {
                if (id == region)
                {
                    String name = (String) patch[0];
                    boolean stands = varbit.applyAsInt((Integer) patch[2]) == GROWN;
                    if (stands == grown.contains(name))
                    {
                        return false;
                    }
                    Set<String> next = new TreeSet<>(grown);
                    if (stands)
                    {
                        next.add(name);
                    }
                    else
                    {
                        next.remove(name);
                    }
                    grown = Collections.unmodifiableSet(new LinkedHashSet<>(next));
                    return true;
                }
            }
        }
        return false;
    }
}
