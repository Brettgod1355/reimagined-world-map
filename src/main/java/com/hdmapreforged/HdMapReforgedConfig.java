package com.hdmapreforged;

import java.awt.*;
import java.awt.event.*;
import lombok.*;
import net.runelite.client.config.*;

@ConfigGroup(HdMapReforgedConfig.GROUP)
public interface HdMapReforgedConfig extends Config
{
    String GROUP = "hdmapreforged";
    int MIN_DROP_VALUE = 1000;

    @ConfigSection(name = "Icons", description = "Which icons the map shows", position = 0)
    String icons = "icons";

    @ConfigSection(name = "Map", description = "Map behaviour and appearance", position = 1)
    String map = "map";

    @ConfigSection(name = "Data and downloads", description = "What is downloaded, and how it is kept", position = 2)
    String data = "data";

    @ConfigSection(name = "Route", description = "Right-click the map, Path to here. \"Own:\" settings are for this "
        + "plugin's planner. It only shows the way; nothing is walked or clicked for you", position = 3)
    String route = "route";

    @ConfigSection(name = "Friends", description = "Party members on the map, through RuneLite's party service",
        position = 4)
    String friends = "friends";

    @ConfigItem(keyName = "showTeleports", name = "Teleports", position = 0, section = icons,
        description = "Destinations of teleport spells, items and minigame teleports")
    default boolean showTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "showTransports", name = "Transport networks", position = 1, section = icons,
        description = "Fairy rings, spirit trees, gliders, balloons, quetzals, boats, carts, carpets, portals and levers")
    default boolean showTransports()
    {
        return true;
    }

    @ConfigItem(keyName = "showDungeons", name = "Dungeon entrances", position = 2, section = icons,
        description = "Ladders, stairs and holes that lead to another map. Click one to look inside")
    default boolean showDungeons()
    {
        return true;
    }

    @ConfigItem(keyName = "showSailing", name = "Sailing", position = 3, section = icons,
        description = "Moorings and salvage spots, with the Sailing level they need")
    default boolean showSailing()
    {
        return true;
    }

    @ConfigItem(keyName = "showSkilling", name = "Skilling", position = 4, section = icons,
        description = "Runecrafting altars, agility courses and shortcuts, and farming patches")
    default boolean showSkilling()
    {
        return true;
    }

    @ConfigItem(keyName = "showActivities", name = "Minigames", position = 5, section = icons,
        description = "Minigame locations")
    default boolean showActivities()
    {
        return true;
    }

    @ConfigItem(keyName = "showServices", name = "Banks, altars and anvils", position = 6, section = icons,
        description = "Shown when zoomed in")
    default boolean showServices()
    {
        return true;
    }

    @ConfigItem(keyName = "showGameIcons", name = "Game map icons", position = 7, section = icons,
        description = "The game's own map icons (shops, quest starts, map links and more): hover for the name, click "
            + "for details")
    default boolean showGameIcons()
    {
        return true;
    }

    @ConfigItem(keyName = "onlyUsable", name = "Only what I can use", position = 8, section = icons,
        description = "Hide icons and routes whose skill, quest or unlock requirements you do not meet (items are not "
            + "checked; needs you logged in)")
    default boolean onlyUsable()
    {
        return false;
    }

    @Range(min = 10, max = 40)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "iconSize", name = "Icon size", position = 9, section = icons,
        description = "Size of map icons. The game's own icons grow in whole steps to stay sharp: 15 up to 22, 30 "
            + "from 23, 45 from 38")
    default int iconSize()
    {
        return 18;
    }

    @ConfigItem(keyName = "orbOpensMap", name = "World map orb opens this map", position = -3,
        description = "Left-clicking the world map orb opens this map over the game view; the game's map stays under "
            + "right-click \"World Map\"")
    default boolean orbOpensMap()
    {
        return false;
    }

    @ConfigItem(keyName = "openMapKey", name = "Open map hotkey",
        description = "Opens and closes the map over the game view; Escape also closes it. Not while typing in the game")
    default Keybind openMapKey()
    {
        return new Keybind(KeyEvent.VK_M, 0);
    }

    /** Icon kinds whose destination lines are hidden. */
    @ConfigItem(keyName = "linesOff", name = "", description = "", hidden = true)
    default String linesOff()
    {
        return "FAIRY_RING";
    }

    @ConfigItem(keyName = "fullMapBounds", name = "", description = "", hidden = true)
    default String fullMapBounds()
    {
        return "";
    }

    @ConfigItem(keyName = "followPlayer", name = "Follow player", position = 0, section = map,
        description = "Keep your character in view, on the map you are in. Dragging the map pauses this")
    default boolean followPlayer()
    {
        return true;
    }

    @ConfigItem(keyName = "showLabels", name = "Place names", position = 1, section = map,
        description = "Names of kingdoms, seas and towns, and of smaller places when zoomed in")
    default boolean showLabels()
    {
        return true;
    }

    @ConfigItem(keyName = "showWilderness", name = "Wilderness levels", position = 2, section = map,
        description = "Lines every five Wilderness levels, with the level 20 and 30 teleport limits marked")
    default boolean showWilderness()
    {
        return true;
    }

    @ConfigItem(keyName = "crispPixels", name = "Sharp pixels when zoomed in", position = 3, section = map,
        description = "Crisp squares instead of smoothing beyond the most detailed zoom level")
    default boolean crispPixels()
    {
        return true;
    }

    @Range(min = 64, max = 1024)
    @ConfigItem(keyName = "memoryTiles", name = "Tiles kept in memory", position = 4, section = map,
        description = "Each tile uses about 256 KB; a large open map may keep more (up to 128 MB), a closed map 64")
    default int memoryTiles()
    {
        return 256;
    }

    @ConfigItem(keyName = "diskCache", name = "Cache tiles on disk", position = 1, section = data,
        description = "Keep downloaded tiles in .runelite/plugin-data/reimagined-world-map so they load instantly next time")
    default boolean diskCache()
    {
        return true;
    }

    @Range(min = 100, max = 10000)
    @Units(" MB")
    @ConfigItem(keyName = "diskCacheMb", name = "Disk cache size", position = 2, section = data,
        description = "The least recently downloaded tiles are removed above this size")
    default int diskCacheMb()
    {
        return 1000;
    }

    @RequiredArgsConstructor
    enum UpdateCheck
    {
        EVERY_START("Every start", 0),
        DAILY("Daily", 1),
        WEEKLY("Weekly", 7),
        NEVER("Never", -1);

        private final String name;
        final int days;

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "mapUpdateCheck", name = "Check for a new map", position = 3, section = data,
        description = "How often to ask the map server for a new map (after a game update); the old map shows until "
            + "the new tiles arrive")
    default UpdateCheck mapUpdateCheck()
    {
        return UpdateCheck.WEEKLY;
    }

    @ConfigItem(keyName = "checkMapNow", name = "Check for a new map now", position = 4, section = data,
        description = "Each click asks the map server for a new map right away")
    default boolean checkMapNow()
    {
        return false;
    }

    @ConfigItem(keyName = "partyShowMembers", name = "Show party members", position = 0, section = friends,
        description = "Show where your RuneLite party's members are (join one in RuneLite's Party panel)")
    default boolean partyShowMembers()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareGear", name = "Share inventory, equipment and skills", position = 2,
        section = friends, description = "While in a party, its members can see what you carry and wear, your skills "
            + "and experience, and your run and special attack energy. Sent on change, at most every 6 seconds; "
            + "nothing is sent outside a party")
    default boolean partyShareGear()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareDrops", name = "Share valuable drops", position = 3, section = friends,
        description = "While in a party, drops worth at least the value below show above you on its members' maps (up "
            + "to 4 per drop). Nothing is sent outside a party")
    default boolean partyShareDrops()
    {
        return true;
    }

    @Range(min = MIN_DROP_VALUE)
    @ConfigItem(keyName = "partyDropValue", name = "Valuable drop from", position = 4, section = friends,
        description = "The least value in coins of one item stack to share it")
    default int partyDropValue()
    {
        return 100_000;
    }

    @ConfigItem(keyName = "partyShareLocation", name = "Share my location", position = 1, section = friends,
        description = "While in a party, send your location to its members so their map shows you. Nothing is sent "
            + "outside a party")
    default boolean partyShareLocation()
    {
        return true;
    }

    @ConfigItem(keyName = "partyFavourites", name = "Favourite friends", position = 6, section = friends,
        description = "Character names, separated by commas: always shown with their name and a star, on top")
    default String partyFavourites()
    {
        return "";
    }

    @ConfigItem(keyName = "partyFriendsTab", name = "Friends button on the map", position = 8, section = friends,
        description = "In a party, a button at the map's bottom left lists the members and their worlds; click one to "
            + "see where they are and what they carry")
    default boolean partyFriendsTab()
    {
        return true;
    }

    @ConfigItem(keyName = "partyOnlyFavourites", name = "Only show favourites", position = 7, section = friends,
        description = "Leave other party members off the map, for example in a big raid party")
    default boolean partyOnlyFavourites()
    {
        return false;
    }

    @RequiredArgsConstructor
    enum RoutePlanner
    {
        OWN("Reimagined World Map"),
        SHORTEST_PATH("Shortest Path plugin");

        private final String name;

        @Override
        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "routePlanner", name = "Route planner", position = -2, section = route,
        description = "Who plans \"Path to here\": this plugin, or the Shortest Path plugin if you have it. With this "
            + "plugin chosen and Shortest Path on, both routes are drawn")
    default RoutePlanner routePlanner()
    {
        return RoutePlanner.OWN;
    }

    @ConfigItem(keyName = "routeFollowPlugins", name = "Follow other plugins' directions", section = route,
        description = "With this plugin as planner, also plan a route to directions other plugins (such as Quest "
            + "Helper) send to Shortest Path")
    default boolean routeFollowPlugins()
    {
        return true;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTeleport", name = "Own: Teleport if it saves", position = 13, section = route,
        description = "Take a teleport only when it saves at least this many tiles of running")
    default int routeSaveTeleport()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShortcut", name = "Own: Shortcut if it saves", position = 14, section = route,
        description = "Take an agility shortcut only when it saves at least this many tiles")
    default int routeSaveShortcut()
    {
        return 0;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTransport", name = "Own: Transport if it saves", position = 15, section = route,
        description = "Take fairy rings, spirit trees, gliders and other transport only when they save at least this "
            + "many tiles")
    default int routeSaveTransport()
    {
        return 5;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCanoe", name = "Own: Canoe if it saves", position = 16, section = route,
        description = "Take a canoe only when it saves at least this many tiles")
    default int routeSaveCanoe()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCarpet", name = "Own: Magic carpet if it saves", position = 17, section = route,
        description = "Take a magic carpet only when it saves at least this many tiles")
    default int routeSaveCarpet()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShip", name = "Own: Boat if it saves", position = 18, section = route,
        description = "Take boats and charter ships only when they save at least this many tiles")
    default int routeSaveShip()
    {
        return 10;
    }

    @ConfigItem(keyName = "routeNeverUse", name = "Own: Never use", position = 12, section = route,
        description = "Teleports and transports never taken, one per line; a whole kind as \"type:Hot air balloon\". "
            + "Add them with a route step's ⋯ button")
    default String routeNeverUse()
    {
        return "";
    }

    @ConfigItem(keyName = "routeIgnoreLevels", name = "Own: Ignore levels", position = 10, section = route,
        description = "Plan as if every level, quest and unlock were met, to see the fastest way there is")
    default boolean routeIgnoreLevels()
    {
        return false;
    }

    @ConfigItem(keyName = "routeIgnoreItems", name = "Own: Ignore items", position = 11, section = route,
        description = "Plan as if you had every teleport item, rune and fare with you, to see the fastest way there is")
    default boolean routeIgnoreItems()
    {
        return false;
    }

    @ConfigItem(keyName = "routeTeleports", name = "Own: Use teleports", position = 0, section = route,
        description = "Let routes use teleports whose runes or item you carry and whose requirements you meet")
    default boolean routeTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "routeSailing", name = "Own: Sail my boat", position = 1, section = route,
        description = "Let routes sail your own boat, within your Sailing level (needs Pandemonium done)")
    default boolean routeSailing()
    {
        return true;
    }

    @ConfigItem(keyName = "routeSailToLand", name = "Own: Sail on land routes", position = 8, section = route,
        description = "Let routes to land sail too. Off, they sail only to or from the sea, or where nothing else "
            + "gets there (islands)")
    default boolean routeSailToLand()
    {
        return false;
    }

    @ConfigItem(keyName = "routeTips", name = "Own: Tips to bring items", position = 9, section = route,
        description = "A line under the route when bringing something (coins, runes) would get you there notably "
            + "sooner")
    default boolean routeTips()
    {
        return true;
    }

    @ConfigItem(keyName = "routeRunning", name = "Own: Running", position = 3, section = route,
        description = "Count on running (two tiles a tick) for how long a route takes")
    default boolean routeRunning()
    {
        return true;
    }

    @ConfigItem(keyName = "routeAvoidWilderness", name = "Own: Avoid the Wilderness", position = 7, section = route,
        description = "Keep routes out of the Wilderness (but the Ferox Enclave), unless they start or end in it or "
            + "no other way gets there")
    default boolean routeAvoidWilderness()
    {
        return true;
    }

    @ConfigItem(keyName = "routeClearOnArrival", name = "Own: Done when arrived", position = 6, section = route,
        description = "Remove the route once you are within a few tiles of where it leads")
    default boolean routeClearOnArrival()
    {
        return true;
    }

    @ConfigItem(keyName = "routeHdTiles", name = "Own: Tiles by HD World Markers", position = 5, section = route,
        description = "With the HD World Markers plugin on, it draws the route's ground tiles (sharper, also when "
            + "stretched)")
    default boolean routeHdTiles()
    {
        return true;
    }

    @ConfigItem(keyName = "routeInGame", name = "Own: Show in the game", position = 4, section = route,
        description = "Also mark the route's tiles on the ground and on the minimap")
    default boolean routeInGame()
    {
        return true;
    }

    @ConfigItem(keyName = "routeColor", name = "Route colour", position = 20, section = route,
        description = "Colour of the route's walks on the map, the ground and the minimap")
    default Color routeColor()
    {
        return RouteFeature.WALK;
    }

    @ConfigItem(keyName = "routeJumpColor", name = "Teleport colour", position = 21, section = route,
        description = "Colour of the route's teleports, transports and passages")
    default Color routeJumpColor()
    {
        return RouteFeature.JUMP;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileFill", name = "Tile fill opacity", position = 22, section = route,
        description = "How much the route's ground tiles are filled: 0 is only the border, 255 solid")
    default int routeTileFill()
    {
        return 75;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileBorder", name = "Tile border opacity", position = 23, section = route,
        description = "How strong the border of the route's ground tiles is: 0 is no border")
    default int routeTileBorder()
    {
        return 0;
    }

    @ConfigItem(keyName = "routeTileWidth", name = "Tile border width", position = 24, section = route,
        description = "Border width of the route's ground tiles")
    default double routeTileWidth()
    {
        return 1.5;
    }

    @ConfigItem(keyName = "routeMinimap", name = "Route on the minimap", position = 25, section = route,
        description = "Dots on the minimap for the route's tiles")
    default boolean routeMinimap()
    {
        return true;
    }
}
