package com.hdmapreforged.route;

import java.util.*;

/**
 * The player-owned house is an instance far from its town, so routes start from its ways out: the exit portal and,
 * in the player's own house only, the portals and such seen there. Teleporting in or building mode means one's own
 * house; entering by a house portal may be a friend's. Client thread only.
 */
public final class HouseTracker
{
    /** By varbit 2187 (POH_HOUSE_LOCATION). */
    private static final int[][] PORTALS = {
        null,
        {2953, 3224}, // Rimmington
        {2893, 3465}, // Taverley
        {3340, 3003}, // Pollnivneach
        {2670, 3631}, // Rellekka
        {2757, 3178}, // Brimhaven
        {2544, 3096}, // Yanille
        {3239, 6076}, // Prifddinas
        {1743, 3517}, // Hosidius
        {1422, 2963}, // Aldarin
    };
    private static final int PORTAL_RADIUS = 12;
    /** How long a choice at a house portal is kept: the walk to the portal, not a later visit. */
    private static final int CHOICE_TICKS = 30;

    private boolean inside;
    private int lastOutside = -1;
    private int exit = -1;
    private boolean own;
    /** Varbit 2187: the town of one's own house. */
    private int location;
    /** The scene is to be looked through: the house's objects load before the tick that finds the player inside. */
    private boolean scan;
    /** Building mode shows empty hotspots (door and stair spaces): not looked through then. */
    private boolean building;
    /** Chosen at a house portal: above 0 one's own house, below 0 a friend's; counts down to 0 every tick. */
    private int chosen;
    private final Set<String> features = new LinkedHashSet<>();
    private boolean featuresChanged;

    public static boolean isTemplate(int x, int y)
    {
        // Where the game copies its rooms from.
        return x >= 1856 && x <= 2047 && y >= 7040 && y <= 7119;
    }

    public static int portal(int location)
    {
        return location > 0 && location < PORTALS.length ? Tiles.pack(PORTALS[location][0], PORTALS[location][1], 0) : -1;
    }

    static int portalNear(int node)
    {
        for (int i = 1; i < PORTALS.length; i++)
        {
            if (Tiles.z(node) == 0 && Math.abs(Tiles.x(node) - PORTALS[i][0]) <= PORTAL_RADIUS
                && Math.abs(Tiles.y(node) - PORTALS[i][1]) <= PORTAL_RADIUS)
            {
                return i;
            }
        }
        return 0;
    }

    /** Every tick. */
    public void update(int node, boolean instance, int houseLocation, boolean buildingMode)
    {
        location = houseLocation;
        building = buildingMode;
        boolean nowInside = instance && node >= 0 && isTemplate(Tiles.x(node), Tiles.y(node));
        if (nowInside && !inside)
        {
            int entered = lastOutside >= 0 ? portalNear(lastOutside) : 0;
            // Through a house portal: maybe a friend's house, unless the player chose their own there. Not known
            // where from (started in a house): not taken for one's own, or a friend's house would be kept as it.
            own = lastOutside >= 0 && (entered <= 0 || chosen > 0);
            exit = portal(own ? houseLocation : entered);
            scan = true;
            // A choice counts for one entry: back at the portal, a friend's house may be next.
            chosen = 0;
        }
        if (nowInside && buildingMode && !own)
        {
            own = true;
            scan = true;
        }
        if (!nowInside && node >= 0 && !instance)
        {
            lastOutside = node;
        }
        chosen -= Integer.signum(chosen);
        inside = nowInside;
    }

    public boolean inside()
    {
        return inside;
    }

    public boolean own()
    {
        return inside && own;
    }

    public int exit()
    {
        return exit;
    }

    /** The portal of one's own house, -1 while its town is not known. */
    public int portal()
    {
        return portal(location);
    }

    /** Whether to look through the scene for the house's features now: once per visit or reload of one's own house. */
    public boolean takeScan()
    {
        boolean due = scan && own() && !building;
        scan &= !due;
        return due;
    }

    /** At a house portal: "Home", "Build mode" or "Go to your house" (true), or a friend's house (false). */
    public void chose(boolean ownHouse)
    {
        chosen = ownHouse ? CHOICE_TICKS : -CHOICE_TICKS;
    }

    /** Looked through too early (building mode's spaces still there): again next tick. */
    public void scanAgain()
    {
        scan = true;
    }

    /** The scene loaded again: building mode, or a teleport from a friend's house (only one's own is reached so). */
    public void reloaded()
    {
        if (inside && !own)
        {
            own = true;
            exit = portal(location);
        }
        scan = inside;
    }

    /** Remembered only in the player's own house, not in building mode (its empty spaces); true when this added one. */
    public boolean seen(String objectName)
    {
        if (!own() || building || objectName == null)
        {
            return false;
        }
        String feature = feature(objectName);
        if (feature != null && features.add(feature))
        {
            featuresChanged = true;
            return true;
        }
        return false;
    }

    public static String feature(String objectName)
    {
        String n = objectName.trim();
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" space"))
        {
            // Building mode's empty spaces ("Jewellery box space"): nothing built there.
            return null;
        }
        if (lower.startsWith("portal nexus: "))
        {
            // A place of the nexus, as the scan names it: apart from the portals.
            return "nexus:" + n.substring("portal nexus: ".length()).trim();
        }
        if (lower.endsWith(" portal") && !lower.startsWith("exit") && !lower.equals("portal") && !lower.contains("nexus"))
        {
            return "portal:" + n.substring(0, n.length() - " portal".length()).trim();
        }
        if (lower.contains("jewellery box"))
        {
            return lower.startsWith("ornate") ? "box:ornate" : lower.startsWith("fancy") ? "box:fancy" : "box:basic";
        }
        return lower.equals("fairy ring") || lower.equals("spirit tree") || lower.equals("obelisk") ? lower
            : lower.equals("spiritual fairy tree") ? "spirit tree+fairy ring"
            : lower.contains("amulet of glory") ? "glory" : lower.contains("xeric's talisman") ? "xeric"
            : lower.contains("digsite pendant") ? "digsite" : null;
    }

    public Set<String> features()
    {
        return Collections.unmodifiableSet(features);
    }

    public void restore(Set<String> saved)
    {
        features.clear();
        for (String f : saved)
        {
            if (f.startsWith("portal:") || f.startsWith("nexus:") || f.startsWith("box:")
                || Set.of("fairy ring", "spirit tree", "spirit tree+fairy ring", "glory", "xeric", "digsite", "obelisk")
                .contains(f))
            {
                features.add(f);
            }
        }
        featuresChanged = false;
    }

    public boolean takeChanged()
    {
        boolean changed = featuresChanged;
        featuresChanged = false;
        return changed;
    }

    public void replaceFeatures(Collection<String> known)
    {
        features.clear();
        features.addAll(known);
        featuresChanged = false;
    }

    public void reset()
    {
        inside = false;
        lastOutside = -1;
        exit = -1;
        own = false;
        location = 0;
        scan = false;
        chosen = 0;
        replaceFeatures(Collections.emptySet());
    }
}
