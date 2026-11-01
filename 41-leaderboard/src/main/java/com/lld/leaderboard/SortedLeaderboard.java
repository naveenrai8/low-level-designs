package com.lld.leaderboard;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The single-threaded leaderboard: a hash map for "what is this user's current entry" plus an order-statistic tree for "where does it sit".
 *
 * <p>Every operation is O(log n) except {@code top}/{@code range}, which are O(log n + rows returned). A score change removes the old entry from the tree and inserts a new one, so there is never a duplicate and {@link #size()} always equals the number of users.
 *
 * <p>Not thread safe. Wrap it in {@link ConcurrentLeaderboard} to share between threads.
 */
public final class SortedLeaderboard implements Leaderboard {

    /** The tree key: score, the time the score was reached, and the user id. */
    record Entry(long score, long reachedAtMillis, String user) {
    }

    /** The tie rule: higher score first, then earlier reach time, then smaller user id. */
    static final Comparator<Entry> ORDER = Comparator
            .comparingLong(Entry::score).reversed()
            .thenComparingLong(Entry::reachedAtMillis)
            .thenComparing(Entry::user);

    private final Clock clock;
    private final Map<String, Entry> byUser = new HashMap<>();
    private final OrderStatisticTree<Entry> tree;

    /** Creates an empty board that reads "now" from {@code clock} when a score changes. */
    public SortedLeaderboard(Clock clock) {
        this(clock, ORDER);
    }

    /** Test seam: lets a test pass a comparator that counts comparisons, to measure the cost of each operation. */
    SortedLeaderboard(Clock clock, Comparator<Entry> order) {
        this.clock = clock;
        this.tree = new OrderStatisticTree<>(order, 42L);
    }

    /** Sets the score; see {@link Leaderboard#setScore}. */
    @Override
    public void setScore(String user, long score) {
        check(user);
        Entry old = byUser.get(user);
        if (old != null && old.score() == score) {
            return;
        }
        if (old != null) {
            tree.remove(old);
        }
        Entry fresh = new Entry(score, clock.millis(), user);
        tree.insert(fresh);
        byUser.put(user, fresh);
    }

    /** Adds to the score and returns the new one; see {@link Leaderboard#increment}. */
    @Override
    public long increment(String user, long delta) {
        check(user);
        Entry old = byUser.get(user);
        long next = Math.addExact(old == null ? 0L : old.score(), delta);
        setScore(user, next);
        return next;
    }

    /** Removes the user; see {@link Leaderboard#remove}. */
    @Override
    public boolean remove(String user) {
        check(user);
        Entry old = byUser.remove(user);
        if (old == null) {
            return false;
        }
        tree.remove(old);
        return true;
    }

    /** Looks up a score; see {@link Leaderboard#score}. */
    @Override
    public OptionalLong score(String user) {
        check(user);
        Entry e = byUser.get(user);
        return e == null ? OptionalLong.empty() : OptionalLong.of(e.score());
    }

    /** Returns the unique 1-based position; see {@link Leaderboard#rank}. */
    @Override
    public OptionalInt rank(String user) {
        check(user);
        Entry e = byUser.get(user);
        return e == null ? OptionalInt.empty() : OptionalInt.of(tree.countLess(e) + 1);
    }

    /** Returns the shared "1224" rank; see {@link Leaderboard#competitionRank}. */
    @Override
    public OptionalInt competitionRank(String user) {
        check(user);
        Entry e = byUser.get(user);
        if (e == null) {
            return OptionalInt.empty();
        }
        // A probe that sorts before every real entry with the same score, so countLess counts only strictly higher scores.
        Entry probe = new Entry(e.score(), Long.MIN_VALUE, "");
        return OptionalInt.of(tree.countLess(probe) + 1);
    }

    /** Returns the best k rows; see {@link Leaderboard#top}. */
    @Override
    public List<Ranked> top(int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0: " + k);
        }
        return rows(0, k);
    }

    /** Returns rows by 1-based rank range; see {@link Leaderboard#range}. */
    @Override
    public List<Ranked> range(int fromRank, int toRank) {
        if (fromRank < 1 || toRank < fromRank) {
            throw new IllegalArgumentException("bad range " + fromRank + ".." + toRank);
        }
        return rows(fromRank - 1, toRank);
    }

    /** Returns the number of users; see {@link Leaderboard#size}. */
    @Override
    public int size() {
        return byUser.size();
    }

    /** Turns tree positions [from, to) (0-based) into rows carrying their 1-based rank. */
    private List<Ranked> rows(int from, int to) {
        List<Entry> slice = tree.slice(from, to);
        List<Ranked> out = new ArrayList<>(slice.size());
        int rank = from + 1;
        for (Entry e : slice) {
            out.add(new Ranked(rank++, e.user(), e.score(), e.reachedAtMillis()));
        }
        return out;
    }

    /** Number of tree nodes touched since the last reset, for tests that bound the work of rank and top-K. */
    long treeVisits() {
        return tree.visits();
    }

    /** Resets the tree's node-visit counter to zero. */
    void resetTreeVisits() {
        tree.resetVisits();
    }

    /** Height of the underlying tree, exposed for tests that check it stays logarithmic. */
    int treeHeight() {
        return tree.height();
    }

    /** Rejects null and blank user ids. */
    private static void check(String user) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank");
        }
    }
}
