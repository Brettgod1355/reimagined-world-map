package com.hdmapreforged;

import java.io.*;
import java.nio.charset.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.util.*;

/** The map server's map version as last seen, and when it was checked, kept on disk between sessions. */
@Slf4j
@RequiredArgsConstructor
final class KnownVersion
{
    static final String FILE = "server-map-version.txt";
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    final String version;
    final long checkedAt;

    /** Weekly; a check time in the future (a changed clock) counts as due. */
    boolean due(long now)
    {
        return now < checkedAt || now - checkedAt >= 7 * DAY_MS;
    }

    /** Null when missing or unusable. */
    static KnownVersion read(Filepath file)
    {
        if (!file.isFile())
        {
            return null;
        }
        try
        {
            String[] lines = new String(TileCache.read(file), StandardCharsets.UTF_8).split("\n");
            String version = lines[0].trim();
            return lines.length < 2 || !MapServer.isSafeVersion(version) ? null
                : new KnownVersion(version, Long.parseLong(lines[1].trim()));
        }
        catch (IOException | NumberFormatException e)
        {
            log.debug("Ignoring unreadable {}", file, e);
            return null;
        }
    }

    static void write(Filepath file, String version, long checkedAt)
    {
        try
        {
            TileCache.writeAtomically(file, (version + "\n" + checkedAt + "\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            log.debug("Could not save the map version", e);
        }
    }
}
