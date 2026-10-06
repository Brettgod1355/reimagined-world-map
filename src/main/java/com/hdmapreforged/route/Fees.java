package com.hdmapreforged.route;

import java.util.*;
import lombok.*;

/** Entrance fees the plugin cannot check ({@code fees.tsv}), weighed as extra time so a free way wins. */
public final class Fees
{
    private static final int NEAR = 2;

    @RequiredArgsConstructor
    public static final class Fee
    {
        final int x;
        final int y;
        final int plane;
        /** Extra half ticks. */
        public final int cost;
        public final String text;
    }

    private static final RouteFiles.Table<List<Fee>> FEES = new RouteFiles.Table<>(Fees::load);

    private Fees()
    {
    }

    public static Fee at(int x, int y, int plane)
    {
        for (Fee fee : FEES.get())
        {
            if (fee.plane == plane && Math.abs(fee.x - x) <= NEAR && Math.abs(fee.y - y) <= NEAR)
            {
                return fee;
            }
        }
        return null;
    }

    private static List<Fee> load()
    {
        List<Fee> fees = new ArrayList<>();
        for (String[] parts : Pathfinder.rows("/com/hdmapreforged/route/fees.tsv"))
        {
            if (parts.length < 3)
            {
                continue;
            }
            try
            {
                int[] xyz = Pathfinder.ints(parts[0]);
                fees.add(new Fee(xyz[0], xyz[1], xyz[2], Integer.parseInt(parts[1].trim()), parts[2].trim()));
            }
            catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
            {
                // Not a fee.
            }
        }
        return Collections.unmodifiableList(fees);
    }
}
