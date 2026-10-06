package com.hdmapreforged;

import lombok.*;
import java.util.*;
import net.runelite.api.coords.*;

/** An icon on the map. */
@RequiredArgsConstructor
final class Poi
{
    @RequiredArgsConstructor
    static final class Link
    {
        final String label;
        final WorldPoint point;
        /** Null when no map draws it. */
        final BaseMap map;
        final Needs needs;
    }

    final PoiType type;
    final String name;
    final WorldPoint location;
    final BaseMap map;
    /** Highlighted together (such as all Ardougne cloak teleports); may be null. */
    final String group;
    final Needs needs;
    final String wikiQuery;
    /** Where an entrance or passage leads, or null. */
    final BaseMap target;
    final String note;
    private final List<Link> links = new ArrayList<>();
    /**
     * Where the map draws it when not at {@link #location}: a teleport or transport on an upper floor of a building on
     * the surface is drawn on the ground floor (where its map icon stands), so it is seen on the map; routes go to its
     * location. Null: drawn at its location.
     */
    WorldPoint drawnAt;

    /** Where the map draws it. */
    WorldPoint drawn()
    {
        return drawnAt != null ? drawnAt : location;
    }

    /** Teleports stacked on this icon; empty for a single one. */
    private final List<Poi> members = new ArrayList<>();
    /** Other things at the same place this icon stands for. */
    final List<Poi> nearby = new ArrayList<>();

    void addLink(Link link)
    {
        links.add(link);
    }

    List<Link> links()
    {
        return Collections.unmodifiableList(links);
    }

    void addMember(Poi member)
    {
        members.add(member);
    }

    List<Poi> members()
    {
        return members.isEmpty() ? Collections.singletonList(this) : Collections.unmodifiableList(members);
    }

    void addNearby(Poi poi)
    {
        nearby.add(poi);
    }

    List<Poi> nearby()
    {
        return Collections.unmodifiableList(nearby);
    }

    /** Icons with the things they stand for. */
    static List<Poi> flatten(List<Poi> pois)
    {
        List<Poi> all = new ArrayList<>(pois.size() + 16);
        for (Poi poi : pois)
        {
            all.add(poi);
            all.addAll(poi.nearby);
        }
        return all;
    }

    /** A spot of the game on the water (a Barracuda Trial's start, salvage): sailed to, not walked to its shore. */
    boolean atSea()
    {
        return type == PoiType.SALVAGE || name.startsWith("Barracuda Trial");
    }

    boolean hasGroup(String group)
    {
        return memberOf(group) != null;
    }

    Poi memberOf(String group)
    {
        if (group == null)
        {
            return null;
        }
        for (Poi member : members())
        {
            if (group.equals(member.group))
            {
                return member;
            }
        }
        for (Poi other : nearby)
        {
            Poi member = other.memberOf(group);
            if (member != null)
            {
                return member;
            }
        }
        return null;
    }

    boolean isOn(BaseMap view)
    {
        return view != null && map != null && (view.id == BaseMap.FULL || map == view);
    }

    @Override
    public String toString()
    {
        return name;
    }
}
