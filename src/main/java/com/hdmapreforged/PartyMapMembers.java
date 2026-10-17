package com.hdmapreforged;

import com.hdmapreforged.route.*;
import java.awt.image.*;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.coords.*;

/** Last known location of each party member: written from network threads, read on Swing, so all synchronized. */
final class PartyMapMembers
{
    /** Heartbeats come every 30 s; after this without news the marker fades. */
    static final long FRESH_MILLIS = 75_000;
    static final long FADE_MILLIS = 5 * 60_000;
    /** After this without news the member is left off the map (probably crashed). */
    static final long GONE_MILLIS = 30 * 60_000;
    static final float MIN_ALPHA = 0.4f;
    /** The core Party plugin's location messages are coarser; ours win while they keep coming. */
    static final long OWN_MESSAGE_PRIORITY_MILLIS = 60_000;

    @RequiredArgsConstructor
    static final class Marker
    {
        final long id;
        final String name;
        final WorldPoint point;
        final int world;
        final BufferedImage avatar;
        final long ageMillis;
        final float alpha;
        /** In a house, at its portal. */
        final boolean house;

        boolean stale()
        {
            return ageMillis > FRESH_MILLIS;
        }
    }

    static final class Member
    {
        String name;
        WorldPoint point;
        boolean house;
        int world;
        BufferedImage avatar;
        long updated;
        long ownUpdated = Long.MIN_VALUE / 2;
    }

    final Map<Long, Member> members = new HashMap<>();
    final Map<Long, Gear> gear = new HashMap<>();

    static final class Gear
    {
        final int[] inventory;
        final int[] quantities;
        final int[] equipment;
        final int[] levels;
        final int[] boosted;
        /** Null when not shared (an older version). */
        final int[] experience;
        /** 0 to 100, or -1 when not shared. */
        final int run;
        final int special;
        final long at;

        Gear(HdMapPartyGear message, long at)
        {
            inventory = message.inventory();
            quantities = message.quantities();
            equipment = message.equipment();
            levels = message.levels();
            boosted = message.boosted();
            experience = message.experience();
            run = message.runEnergy();
            special = message.specialAttack();
            this.at = at;
        }
    }

    @RequiredArgsConstructor
    static final class Drop
    {
        final int skill;
        final int amount;
        final long at;
    }

    static final long DROP_MS = 2200;
    static final int MAX_DROPS = 16;
    final Map<Long, List<Drop>> drops = new HashMap<>();

    synchronized void gear(long id, Gear shared)
    {
        Gear before = gear.put(id, shared);
        if (before != null && before.experience != null && shared.experience != null)
        {
            var list = drops.computeIfAbsent(id, k -> new ArrayList<>());
            for (int k = 0; k < shared.experience.length; k++)
            {
                int gained = shared.experience[k] - before.experience[k];
                if (gained > 0 && gained < 5_000_000)
                {
                    list.add(new Drop(k, gained, shared.at));
                }
            }
            // Only read while details are open: keep it short either way.
            list.removeIf(d -> shared.at - d.at > DROP_MS);
            if (list.size() > MAX_DROPS)
            {
                list.subList(0, list.size() - MAX_DROPS).clear();
            }
        }
    }

    @RequiredArgsConstructor
    static final class Loot
    {
        final int item;
        final int quantity;
        final long value;
        final long at;
        /** An untradeable drop's name, else null. */
        final String name;
    }

    static final long LOOT_MS = 3500;
    final Map<Long, List<Loot>> loot = new HashMap<>();

    synchronized void loot(long id, Loot drop)
    {
        var list = loot.computeIfAbsent(id, k -> new ArrayList<>());
        list.removeIf(l -> drop.at - l.at > LOOT_MS);
        list.add(drop);
        // A big pile shows its most valuable few.
        if (list.size() > 4)
        {
            list.sort(Comparator.comparingLong((Loot l) -> -l.value));
            list.subList(4, list.size()).clear();
        }
    }

    synchronized List<Loot> loot(long id, long now)
    {
        return recent(loot.get(id), l -> now - l.at > LOOT_MS);
    }

    /** A copy without the old ones, which go. */
    static <T> List<T> recent(List<T> list, Predicate<T> old)
    {
        if (list == null)
        {
            return Collections.emptyList();
        }
        list.removeIf(old);
        return new ArrayList<>(list);
    }

    synchronized List<Drop> drops(long id, long now)
    {
        return recent(drops.get(id), d -> now - d.at > DROP_MS);
    }

    synchronized Gear gear(long id)
    {
        return gear.get(id);
    }

    /** A location from our own message; a null point means they left the game. */
    synchronized void update(long id, WorldPoint point, int world, String name, boolean house, long now)
    {
        Member member = members.computeIfAbsent(id, k -> new Member());
        // From a version that sent the house's room template (far off any map): its last spot outside, in a house.
        boolean template = point != null && HouseTracker.isTemplate(point.getX(), point.getY());
        member.point = template ? member.point : point;
        member.house = house || template;
        member.world = world;
        if (name != null)
        {
            member.name = name;
        }
        member.updated = now;
        member.ownUpdated = now;
    }

    /** A location from RuneLite's Party plugin, used only while no recent message of ours came. */
    synchronized boolean updateFromCore(long id, WorldPoint point, long now)
    {
        Member member = members.computeIfAbsent(id, k -> new Member());
        if (now - member.ownUpdated < OWN_MESSAGE_PRIORITY_MILLIS)
        {
            return false;
        }
        member.point = point;
        member.house = false;
        member.updated = now;
        return true;
    }

    /** From RuneLite's party data, when the member's own messages carry none. */
    synchronized void name(long id, String name)
    {
        Member member = members.get(id);
        name = clean(name);
        if (member != null && member.name == null && name != null)
        {
            member.name = name;
        }
    }

    /** Never HTML in a Swing label; null when nothing is left. */
    static String clean(String name)
    {
        return HdMapPartyLocation.cleaned(name, true);
    }

    synchronized void avatar(long id, BufferedImage avatar)
    {
        Member member = members.get(id);
        if (member != null)
        {
            member.avatar = avatar;
        }
    }

    synchronized void remove(long id)
    {
        List.of(members, gear, drops, loot).forEach(m -> m.remove(id));
    }

    synchronized void clear()
    {
        List.of(members, gear, drops, loot).forEach(Map::clear);
    }

    synchronized boolean isEmpty()
    {
        return members.isEmpty();
    }

    /** Oldest news first so the freshest end up on top. */
    synchronized List<Marker> markers(long now)
    {
        var markers = new ArrayList<Marker>();
        for (var entry : members.entrySet())
        {
            Member member = entry.getValue();
            long age = Math.max(0, now - member.updated);
            if (member.point == null || age > GONE_MILLIS)
            {
                continue;
            }
            markers.add(new Marker(entry.getKey(), member.name, member.point, member.world, member.avatar, age, alpha(age),
                member.house));
        }
        markers.sort((a, b) -> Long.compare(b.ageMillis, a.ageMillis));
        return Collections.unmodifiableList(markers);
    }

    /** Full until FRESH_MILLIS, then fading linearly to MIN_ALPHA over FADE_MILLIS. */
    static float alpha(long ageMillis)
    {
        if (ageMillis <= FRESH_MILLIS)
        {
            return 1f;
        }
        double t = Math.min(1, (ageMillis - FRESH_MILLIS) / (double) FADE_MILLIS);
        return (float) (1 - t * (1 - MIN_ALPHA));
    }
}
