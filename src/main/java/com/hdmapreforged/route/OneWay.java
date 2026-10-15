package com.hdmapreforged.route;

import java.util.*;
import lombok.*;

/** One-way passages ({@code one_way.tsv}, e.g. a slide): a passage of any source going back between the areas is left out. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class OneWay
{
    @RequiredArgsConstructor
    static final class Rule
    {
        final int from;
        final int to;
        final int radius;
    }

    final List<Rule> rules;

    public static OneWay load()
    {
        var rules = new ArrayList<Rule>();
        for (var parts : Pathfinder.rows("/com/hdmapreforged/route/one_way.tsv"))
        {
            Rule rule = parse(parts);
            if (rule != null)
            {
                rules.add(rule);
            }
        }
        return new OneWay(rules);
    }

    /** "x y plane <tab> x y plane <tab> radius <tab> name", split at tabs; null when not a rule. */
    static Rule parse(String[] parts)
    {
        if (parts.length < 3)
        {
            return null;
        }
        int from = Tiles.parse(parts[0]);
        int to = Tiles.parse(parts[1]);
        try
        {
            return from < 0 || to < 0 ? null : new Rule(from, to, Integer.parseInt(parts[2].trim()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    public boolean against(int from, int to)
    {
        for (Rule rule : rules)
        {
            if (near(from, rule.to, rule.radius) && near(to, rule.from, rule.radius))
            {
                return true;
            }
        }
        return false;
    }

    static boolean near(int a, int b, int radius)
    {
        return !Tiles.isSea(a) && !Tiles.isSea(b) && Tiles.z(a) == Tiles.z(b) && Tiles.distance(a, b) <= radius;
    }
}
