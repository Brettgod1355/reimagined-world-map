package com.hdmapreforged;

import java.util.*;
import net.runelite.api.coords.*;

/**
 * Party decisions, free of RuneLite services for testing. Joining is always the user's own action: RuneLite rejects
 * auto-joined parties.
 */
final class PartyMapRules
{
    /** Party Hotkeys waits this long between party changes; so do we. */
    static final long CHANGE_COOLDOWN_MILLIS = 5_000;
    /** RuneLite's Party plugin leaves after 30 minutes logged out; we do the same for a party we rejoined. */
    static final long IDLE_LEAVE_MILLIS = 30 * 60_000;
    /** Every tick something changed, as RuneLite's own Party plugin sends its status. */
    static final int MIN_SEND_TICKS = 1;
    /** Gear at most every three seconds: a message is some 600 bytes, and a fight changes it most ticks. */
    static final int GEAR_TICKS = 5;
    static final int MAX_DROPS_PER_LOOT = 4;
    /** Sent at least this often while standing still, so newcomers and fading work. */
    static final int HEARTBEAT_TICKS = 50;

    enum Join
    {
        JOIN,
        ALREADY_IN,
        CONFIRM_LEAVE_OTHER,
        NOT_LOGGED_IN,
        INVALID_CODE,
        TOO_SOON,
    }

    static Join join(String code, boolean loggedIn, String currentPassphrase, long lastChange, long now)
    {
        return code == null ? Join.INVALID_CODE : code.equals(currentPassphrase) ? Join.ALREADY_IN
            : !loggedIn ? Join.NOT_LOGGED_IN : now - lastChange < CHANGE_COOLDOWN_MILLIS ? Join.TOO_SOON
            : currentPassphrase != null ? Join.CONFIRM_LEAVE_OTHER : Join.JOIN;
    }

    /** Only a party this plugin joined; parties the user joined elsewhere are never left. */
    static boolean leaveIdle(String joinedCode, String currentPassphrase, Long loggedOutSince, long now)
    {
        return leaveOnShutdown(joinedCode, currentPassphrase) && loggedOutSince != null
            && now - loggedOutSince >= IDLE_LEAVE_MILLIS;
    }

    static boolean leaveOnShutdown(String joinedCode, String currentPassphrase)
    {
        return joinedCode != null && joinedCode.equals(currentPassphrase);
    }

    /** {@link #shouldSend} runs on the client thread; party events reset and force it from other threads. */
    static final class Throttle
    {
        WorldPoint lastSent;
        int lastTick = Integer.MIN_VALUE / 2;
        volatile boolean forced;

        void force()
        {
            forced = true;
        }

        synchronized void reset()
        {
            lastSent = null;
            lastTick = Integer.MIN_VALUE / 2;
            forced = false;
        }

        synchronized boolean shouldSend(WorldPoint point, int tick)
        {
            if (point == null)
            {
                return false;
            }
            int since = tick - lastTick;
            boolean moved = !Objects.equals(point, lastSent);
            boolean due = forced || since >= HEARTBEAT_TICKS || moved;
            if (!due || since < MIN_SEND_TICKS)
            {
                return false;
            }
            lastSent = point;
            lastTick = tick;
            forced = false;
            return true;
        }
    }
}
