package com.hdmapreforged;

import lombok.*;

/** A travel table row's requirement columns, kept raw. */
@RequiredArgsConstructor
@EqualsAndHashCode
final class Needs
{
    static final Needs NONE = new Needs("", "", "", "");

    final String skills;
    final String items;
    final String quests;
    final String varbits;

    boolean isEmpty()
    {
        return skills.isEmpty() && items.isEmpty() && quests.isEmpty() && varbits.isEmpty();
    }
}
