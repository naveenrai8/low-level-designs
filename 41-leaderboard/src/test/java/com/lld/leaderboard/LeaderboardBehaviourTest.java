package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plain behaviour of the single-threaded board: scores, ranks, ties, moving entries and error cases. */
class LeaderboardBehaviourTest {

    private MutableClock clock;
    private SortedLeaderboard board;

    /** Gives every test a fresh board on a clock that starts at a fixed instant. */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        board = new SortedLeaderboard(clock);
    }

    /** Advances the fake clock by one second. */
    private void tick() {
        clock.advance(Duration.ofSeconds(1));
    }

    /** Proves the basic flow: set, increment, score lookup and top-K order. */
    @Test
    void setIncrementAndTop() {
        board.setScore("a", 10);
        board.setScore("b", 30);
        assertEquals(25, board.increment("c", 25));
        assertEquals(List.of("b", "c", "a"), board.top(3).stream().map(Ranked::user).toList());
        assertEquals(List.of("b", "c"), board.top(2).stream().map(Ranked::user).toList());
        assertEquals(OptionalLong.of(30), board.score("b"));
        assertEquals(OptionalLong.empty(), board.score("zed"));
    }

    /** Proves a score change moves the entry: no duplicate, size unchanged, new rank correct. */
    @Test
    void updateMovesEntryWithoutDuplicate() {
        board.setScore("a", 10);
        board.setScore("b", 20);
        board.setScore("a", 50);
        assertEquals(2, board.size());
        assertEquals(2, board.top(10).size());
        assertEquals(OptionalInt.of(1), board.rank("a"));
        assertEquals(OptionalInt.of(2), board.rank("b"));
    }

    /** Proves ties break by earlier reach time first, then by user id. */
    @Test
    void tieBreaksByTimeThenUserId() {
        board.setScore("zoe", 100);
        tick();
        board.setScore("amy", 100);
        assertEquals(OptionalInt.of(1), board.rank("zoe"));
        assertEquals(OptionalInt.of(2), board.rank("amy"));
        tick();
        board.setScore("bob", 100);
        board.setScore("abe", 100);
        // bob and abe reached 100 in the same millisecond, so the smaller id wins.
        assertEquals(List.of("zoe", "amy", "abe", "bob"), board.top(4).stream().map(Ranked::user).toList());
    }

    /** Proves writing the same score again keeps the original reach time, so a user cannot lose or gain a tie by re-submitting. */
    @Test
    void sameScoreKeepsOriginalReachTime() {
        board.setScore("early", 5);
        tick();
        board.setScore("late", 5);
        tick();
        board.setScore("early", 5);
        assertEquals(OptionalInt.of(1), board.rank("early"));
    }

    /** Proves the 1224 competition rank: equal scores share a rank and the next one is skipped, while rank() stays unique. */
    @Test
    void competitionRankSharesAndSkips() {
        board.setScore("a", 100);
        board.setScore("b", 90);
        board.setScore("c", 90);
        board.setScore("d", 80);
        assertEquals(OptionalInt.of(1), board.competitionRank("a"));
        assertEquals(OptionalInt.of(2), board.competitionRank("b"));
        assertEquals(OptionalInt.of(2), board.competitionRank("c"));
        assertEquals(OptionalInt.of(4), board.competitionRank("d"));
        assertEquals(OptionalInt.of(3), board.rank("c"));
        assertEquals(OptionalInt.of(4), board.rank("d"));
    }

    /** Proves remove deletes the entry, reports whether it existed, and later ranks close the gap. */
    @Test
    void removeClosesTheGap() {
        board.setScore("a", 3);
        board.setScore("b", 2);
        board.setScore("c", 1);
        assertTrue(board.remove("b"));
        assertFalse(board.remove("b"));
        assertEquals(2, board.size());
        assertEquals(OptionalInt.of(2), board.rank("c"));
        assertEquals(OptionalInt.empty(), board.rank("b"));
    }

    /** Proves range is 1-based, inclusive, carries the right rank numbers and is cut at the board size. */
    @Test
    void rangeIsOneBasedInclusiveAndClipped() {
        for (int i = 1; i <= 5; i++) {
            board.setScore("u" + i, 100 - i);
        }
        List<Ranked> mid = board.range(2, 4);
        assertEquals(List.of(2, 3, 4), mid.stream().map(Ranked::rank).toList());
        assertEquals(List.of("u2", "u3", "u4"), mid.stream().map(Ranked::user).toList());
        assertEquals(2, board.range(4, 99).size());
        assertEquals(0, board.range(9, 12).size());
    }

    /** Proves negative scores and decrements work and sort below zero. */
    @Test
    void negativeScoresAndDecrement() {
        board.increment("a", -5);
        board.increment("b", 0);
        assertEquals(OptionalInt.of(1), board.rank("b"));
        assertEquals(-8, board.increment("a", -3));
    }

    /** Proves overflow is rejected and leaves the old score intact. */
    @Test
    void incrementOverflowThrowsAndChangesNothing() {
        board.setScore("a", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> board.increment("a", 1));
        assertEquals(OptionalLong.of(Long.MAX_VALUE), board.score("a"));
    }

    /** Proves bad arguments are rejected with IllegalArgumentException. */
    @Test
    void badArgumentsThrow() {
        assertThrows(IllegalArgumentException.class, () -> board.setScore(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> board.rank(null));
        assertThrows(IllegalArgumentException.class, () -> board.top(-1));
        assertThrows(IllegalArgumentException.class, () -> board.range(0, 3));
        assertThrows(IllegalArgumentException.class, () -> board.range(3, 2));
    }

    /** Proves top(0) and an empty board return empty lists rather than failing. */
    @Test
    void emptyCases() {
        assertTrue(board.top(5).isEmpty());
        board.setScore("a", 1);
        assertTrue(board.top(0).isEmpty());
    }
}
