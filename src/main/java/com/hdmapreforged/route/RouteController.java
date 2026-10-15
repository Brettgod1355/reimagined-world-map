package com.hdmapreforged.route;

import static com.hdmapreforged.route.Tiles.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import lombok.*;
import lombok.extern.slf4j.*;

/**
 * When to search, so it never happens too often: one search at a time; when the player strays, automatic searches
 * with a doubling pause, at most {@link #MAX_AUTOMATIC} in a row (again once back on the way, or walked on toward the
 * target), none where a search proved the target out of reach (until a user action, or until the ways it may use
 * change) and none in instances other than the house (their positions are template copies). Called on one thread
 * (Swing); results come back through {@code callbacks}, also when a search fails.
 */
@Slf4j
@RequiredArgsConstructor
public final class RouteController
{
    public static final long FIRST_BACKOFF_MS = 3_000;
    public static final long MAX_BACKOFF_MS = 60_000;
    public static final int MAX_AUTOMATIC = 6;
    /** Tiles from the route before the player counts as having left it: 10 away, as Shortest Path. */
    public static final int STRAY = 9;

    final Executor searches;
    final Executor callbacks;
    final Pathfinder pathfinder;
    /** Builds a search for a target, or null when there is no start. */
    final IntFunction<RouteRequest> requests;
    final Consumer<RouteController> changed;
    final LongSupplier clock;

    int target = -1;
    Route route;
    /** What the route's search was asked; whether it was an automatic search asked the same ways as the one before. */
    RouteRequest asked;
    boolean moved;
    AtomicBoolean running;
    /** A user search asked for while another ran; started when that one ends. */
    boolean pendingUser;
    int automatic;
    long backoff = FIRST_BACKOFF_MS;
    long nextAutomatic;
    /** Where the player was when an automatic search was last thought of, and the tick before; -1 none. */
    int searchedAt = -1;
    int lastPlayer = -1;
    /**
     * A search that tried everything without getting there, and what it was asked: from anywhere it reached, the
     * target stays out of reach while the same ways may be used.
     */
    Route unreachable;
    RouteRequest unreachableAsked;
    String status;
    /** A search could not start for want of a position, and when to try again. */
    boolean noStart;
    long nextStartTry;

    public void setTarget(int target)
    {
        userAction();
        this.target = target;
        route = null;
        status = "Searching…";
        start(true);
        changed.accept(this);
    }

    /** A route planned already (a custom route's next part): shown as found, without a search. */
    public void show(int target, Route planned)
    {
        userAction();
        cancelRunning();
        this.target = target;
        route = planned;
        status = null;
        changed.accept(this);
    }

    public void clear()
    {
        userAction();
        target = -1;
        route = null;
        status = null;
        noStart = false;
        cancelRunning();
        changed.accept(this);
    }

    void cancelRunning()
    {
        if (running != null)
        {
            running.set(true);
            running = null;
        }
        pendingUser = false;
    }

    /** A setting that changes routes (or unlocks, bank) changed: search again once. */
    public void settingsChanged()
    {
        userAction();
        if (target >= 0)
        {
            start(true);
        }
    }

    void userAction()
    {
        unreachable = null;
        unreachableAsked = null;
        asked = null;
        moved = false;
        searchedAt = -1;
        freshWaits();
    }

    /** Automatic searches may start at once again, and wait as for the first. */
    void freshWaits()
    {
        automatic = 0;
        backoff = FIRST_BACKOFF_MS;
        nextAutomatic = 0;
    }

    /** {@code player} packed or -1; starts a search when the player left the route. */
    public void playerMoved(int player, boolean automaticAllowed)
    {
        int before = lastPlayer;
        lastPlayer = player;
        if (target >= 0 && running == null && route == null && noStart && player >= 0 && automaticAllowed)
        {
            // Asked for where the position was unknown: retried now and then once it is known.
            long now = clock.getAsLong();
            if (now >= nextStartTry)
            {
                nextStartTry = now + FIRST_BACKOFF_MS;
                status = "Searching…";
                start(true);
                changed.accept(this);
            }
            return;
        }
        if (target < 0 || running != null || !automaticAllowed || player < 0 || route == null)
        {
            return;
        }
        if (onRoute(player))
        {
            // Back on the way: long trips get their automatic searches again.
            backoff = FIRST_BACKOFF_MS;
            automatic = 0;
            return;
        }
        long now = clock.getAsLong();
        if (searchedAt >= 0 && before >= 0 && near(player, before, 4) && !near(player, searchedAt, STRAY)
            && Pathfinder.folded(player, target) < Pathfinder.folded(searchedAt, target))
        {
            // Walked on toward the target past where the last search began (its route came too late to be on): a new
            // stray, no wait. Walking elsewhere keeps the waits and the cap, as does jumping about (a glitching position).
            freshWaits();
        }
        if (automatic >= MAX_AUTOMATIC || now < nextAutomatic)
        {
            return;
        }
        searchedAt = player;
        nextAutomatic = now + backoff;
        var request = requests.apply(target);
        if (request != null && unreachableFrom(request))
        {
            // Out of reach from here with these ways (proved): looked at again after the wait, not searched.
            return;
        }
        automatic++;
        backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        start(request, false);
    }

    /** Whether a search that tried everything reached where this one would start, with the same ways. */
    boolean unreachableFrom(RouteRequest request)
    {
        return unreachable != null && unreachableAsked.sameWays(request)
            && unreachable.reached(pathfinder.startNode(request.start));
    }

    public static final int ARRIVED = 3;

    /** Within {@link #ARRIVED} tiles of the route's end (or the tile asked for), on the same floor. */
    public boolean arrived(int player)
    {
        if (player < 0 || route == null || isSea(player))
        {
            return false;
        }
        // A route that cannot get there ends where it gets stuck: no arrival.
        int end = route.outcome != Route.Outcome.FOUND ? -1 : route.end >= 0 ? route.end : target;
        return end >= 0 && z(end) == z(player) && distance(player, end) <= ARRIVED
            || target >= 0 && z(target) == z(player) && distance(player, target) <= ARRIVED;
    }

    boolean onRoute(int player)
    {
        // With no steps: already there, or no start found, which a later position may fix.
        if (route.end >= 0 && near(player, route.end, STRAY))
        {
            return true;
        }
        for (var step : route.steps)
        {
            if (step.isJump())
            {
                if (near(player, step.last(), STRAY) || step.first() != step.last() && near(player, step.first(), STRAY))
                {
                    return true;
                }
                continue;
            }
            for (int point : step.points)
            {
                if (near(player, point, isSea(point) ? STRAY + CELL : STRAY))
                {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean near(int a, int b, int radius)
    {
        boolean sea = isSea(a) || isSea(b);
        return (sea || z(a) == z(b)) && distance(a, b) <= radius;
    }

    void start(boolean user)
    {
        if (running != null)
        {
            if (user)
            {
                // The newest user request wins: start it when the old one ends.
                running.set(true);
                pendingUser = true;
            }
            return;
        }
        start(requests.apply(target), user);
    }

    void start(RouteRequest request, boolean user)
    {
        noStart = request == null;
        if (request == null)
        {
            status = "Your position is not known here; try again outside";
            changed.accept(this);
            return;
        }
        var cancel = new AtomicBoolean();
        running = cancel;
        int searched = target;
        try
        {
            searches.execute(() -> {
                Route result = null;
                try
                {
                    result = BankDetour.find(pathfinder, request, cancel::get);
                }
                catch (Throwable e)
                {
                    log.warn("Route search to {} failed", format(searched), e);
                }
                finally
                {
                    Route done = result != null ? result : new Route(Route.Outcome.NONE,
                        Collections.emptyList(), 0, searched, -1, false, false, 0);
                    callbacks.execute(() -> finished(cancel, searched, request, user, done));
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            running = null;
        }
    }

    void finished(AtomicBoolean search, int searched, RouteRequest request, boolean user, Route result)
    {
        if (running == search)
        {
            running = null;
        }
        if (pendingUser && running == null)
        {
            pendingUser = false;
            if (target >= 0)
            {
                start(true);
                return;
            }
        }
        if (search.get() || searched != target || result.outcome == Route.Outcome.CANCELLED)
        {
            return;
        }
        moved = !user && asked != null && asked.sameWays(request);
        route = result;
        asked = request;
        if (result.exhausted && result.outcome != Route.Outcome.FOUND)
        {
            unreachable = result;
            unreachableAsked = request;
        }
        status = null;
        changed.accept(this);
    }

    public int target()
    {
        return target;
    }

    public Route route()
    {
        return route;
    }

    /** Whether the route came from an automatic search with the same ways as the one before: only the player moved. */
    public boolean onlyMoved()
    {
        return moved;
    }

    /** A message instead of a route, or null. */
    public String status()
    {
        return status;
    }
}
