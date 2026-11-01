package com.lld.leaderboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * A set of boards, one per calendar window (a day or a week), for "today's top players" style features.
 *
 * <p>Each {@link #increment} goes into the window that contains "now" according to the injected {@link Clock}. A score added exactly at a boundary lands in the new window.
 *
 * <p><b>Expiry is by calendar.</b> With {@code retain = 3}, the kept windows are the newest calendar window and the two calendar windows before it, even if nobody scored in some of them. The newest window is the later of the newest stored window and the window of "now". Anything older is dropped on the next write and is invisible to reads.
 *
 * <p><b>Clock steps.</b> A write whose window is older than the oldest kept window throws {@link StaleWindowException} and changes nothing. After a forward clock jump, writes for the real (earlier) time are refused until the clock catches up, which is loud on purpose, because quietly storing then evicting them would report success for a lost score.
 *
 * <p>All methods are {@code synchronized} on this object, which also guards the inner boards. That is the same single-lock trade-off as {@link ConcurrentLeaderboard}.
 */
public final class WindowedLeaderboard {

    private final Window window;
    private final ZoneId zone;
    private final Clock clock;
    private final int retain;
    private final TreeMap<Instant, SortedLeaderboard> boards = new TreeMap<>();

    /** Creates the board set. {@code retain} is how many calendar windows to keep (at least 1, the current one counts). */
    public WindowedLeaderboard(Window window, ZoneId zone, Clock clock, int retain) {
        if (retain < 1) {
            throw new IllegalArgumentException("retain must be >= 1");
        }
        this.window = window;
        this.zone = zone;
        this.clock = clock;
        this.retain = retain;
    }

    /** Adds {@code delta} to the user's score in the window that contains now, and returns the user's new score in that window. Throws {@link StaleWindowException} if that window is older than the oldest kept one. */
    public synchronized long increment(String user, long delta) {
        Instant start = window.startOf(clock.instant(), zone);
        Instant oldestKept = oldestKept();
        if (start.isBefore(oldestKept)) {
            throw new StaleWindowException("window " + start + " is older than the oldest kept window " + oldestKept);
        }
        SortedLeaderboard board = boards.computeIfAbsent(start, s -> new SortedLeaderboard(clock));
        long result = board.increment(user, delta);
        boards.headMap(oldestKept()).clear();
        return result;
    }

    /** Returns the best {@code k} rows of the window containing {@code at}. Empty if that window never had a score or has expired. */
    public synchronized List<Ranked> top(Instant at, int k) {
        SortedLeaderboard board = find(at);
        return board == null ? List.of() : board.top(k);
    }

    /** Returns the user's position in the window containing {@code at}, or empty if the user, or the window, is missing or expired. */
    public synchronized OptionalInt rank(Instant at, String user) {
        SortedLeaderboard board = find(at);
        return board == null ? OptionalInt.empty() : board.rank(user);
    }

    /** Returns the start instants of the windows currently stored, oldest first. */
    public synchronized List<Instant> windowStarts() {
        return new ArrayList<>(boards.keySet());
    }

    /** Returns the board for the window containing {@code at}, or null if it is missing or older than the oldest kept window. */
    private SortedLeaderboard find(Instant at) {
        Instant start = window.startOf(at, zone);
        return start.isBefore(oldestKept()) ? null : boards.get(start);
    }

    /** Returns the start of the oldest calendar window still kept: {@code retain - 1} windows before the newest one, where newest is the later of the newest stored window and the window of now. */
    private Instant oldestKept() {
        Instant newest = window.startOf(clock.instant(), zone);
        if (!boards.isEmpty() && boards.lastKey().isAfter(newest)) {
            newest = boards.lastKey();
        }
        Instant oldest = newest;
        for (int i = 1; i < retain; i++) {
            oldest = window.previousStart(oldest, zone);
        }
        return oldest;
    }
}
