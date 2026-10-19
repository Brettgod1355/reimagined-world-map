package com.hdmapreforged;

import lombok.*;
import net.runelite.client.party.messages.*;

/** A valuable drop a party member just got, sent once to the party. Values from other clients are checked. */
@AllArgsConstructor
public class HdMapPartyDrop extends PartyMemberMessage
{
    /** Item id, quantity, and worth in coins as the sender priced it. */
    private final int i;
    private final int q;
    private final long v;

    /** -1 when not believable. */
    int item()
    {
        return i >= 0 && i < HdMapPartyGear.MAX_ITEM ? i : -1;
    }

    int quantity()
    {
        return Math.max(1, q);
    }

    long value()
    {
        return Math.max(0, Math.min(Integer.MAX_VALUE * 100L, v));
    }
}
