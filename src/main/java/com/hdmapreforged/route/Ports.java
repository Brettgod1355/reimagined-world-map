package com.hdmapreforged.route;

import java.util.*;

/** Where a boat lies at each Sailing port (ports.tsv, from the game cache), keyed by SAILING_BOAT_n_PORT values. */
public final class Ports
{
    public static final String RESOURCE = RouteFiles.DIR + "ports.tsv";

    /** Port id → packed tile of the boat; none without the table. */
    public static Map<Integer, Integer> load()
    {
        var ports = new HashMap<Integer, Integer>();
        for (var c : Pathfinder.rows(RESOURCE))
        {
            try
            {
                // Short rows and blank lines fail here too.
                var p = c[2].trim().split("\\s+");
                ports.put(Integer.parseInt(c[0].trim()), Tiles.pack(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 0));
            }
            catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
            {
                // Skip the row.
            }
        }
        return Collections.unmodifiableMap(ports);
    }
}
