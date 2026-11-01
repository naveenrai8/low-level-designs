package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Time-windowed boards: half-open boundaries, time zones, weekly start, expiry. */
class WindowedLeaderboardTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    /** Proves a score at exactly local midnight goes to the new day and one millisecond before goes to the old day. */
    @Test
    void boundaryInstantBelongsToNextWindow() {
        // New York midnight on 2026-03-02 is 05:00Z (EST).
        Instant midnight = Instant.parse("2026-03-02T05:00:00Z");
        MutableClock clock = new MutableClock(midnight.minusMillis(1));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, NY, clock, 7);
        w.increment("a", 10);
        clock.set(midnight);
        w.increment("a", 5);
        assertEquals(10, w.top(midnight.minusMillis(1), 1).get(0).score());
        assertEquals(5, w.top(midnight, 1).get(0).score());
        assertEquals(2, w.windowStarts().size());
    }

    /** Proves the window follows the board's zone, not UTC: the same instant is two different days in two zones. */
    @Test
    void windowsAreTimeZoneAware() {
        Instant t = Instant.parse("2026-03-02T03:00:00Z");
        assertEquals(Instant.parse("2026-03-02T00:00:00Z"), Window.DAILY.startOf(t, ZoneId.of("UTC")));
        assertEquals(Instant.parse("2026-03-01T05:00:00Z"), Window.DAILY.startOf(t, NY));
    }

    /** Proves a day on a daylight-saving change is 23 hours long, because boundaries are local midnights. */
    @Test
    void dstDayIsTwentyThreeHours() {
        Instant start = Window.DAILY.startOf(Instant.parse("2026-03-08T12:00:00Z"), NY);
        Instant end = Window.DAILY.endOf(Instant.parse("2026-03-08T12:00:00Z"), NY);
        assertEquals(Duration.ofHours(23), Duration.between(start, end));
    }

    /** Proves weekly windows start on Monday local midnight, and Sunday night still belongs to the old week. */
    @Test
    void weeklyStartsOnMonday() {
        // 2026-03-02 is a Monday.
        Instant mondayStart = Instant.parse("2026-03-02T00:00:00Z");
        ZoneId utc = ZoneId.of("UTC");
        assertEquals(mondayStart, Window.WEEKLY.startOf(Instant.parse("2026-03-08T23:59:59Z"), utc));
        assertEquals(mondayStart.plus(Duration.ofDays(7)), Window.WEEKLY.startOf(Instant.parse("2026-03-09T00:00:00Z"), utc));
        assertEquals(mondayStart.plus(Duration.ofDays(7)), Window.WEEKLY.endOf(mondayStart, utc));
    }

    /** Proves old windows are dropped once more than {@code retain} exist, so memory stays bounded. */
    @Test
    void oldWindowsExpire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), clock, 2);
        Instant day1 = clock.instant();
        for (int i = 0; i < 4; i++) {
            w.increment("a", 1);
            clock.advance(Duration.ofDays(1));
        }
        assertEquals(2, w.windowStarts().size());
        assertTrue(w.top(day1, 5).isEmpty());
        assertEquals(OptionalInt.empty(), w.rank(day1, "a"));
        assertEquals(List.of("a"), w.top(clock.instant().minus(Duration.ofDays(1)), 5).stream().map(Ranked::user).toList());
    }

    /** Proves scores in different windows are independent: the same user restarts from zero each day. */
    @Test
    void eachWindowStartsFromZero() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), clock, 3);
        assertEquals(7, w.increment("a", 7));
        clock.advance(Duration.ofDays(1));
        assertEquals(2, w.increment("a", 2));
    }

    /** Proves retain below 1 is rejected. */
    @Test
    void retainMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), new MutableClock(Instant.EPOCH), 0));
    }

    /** Proves rank works on a live window and is empty for an unknown user. */
    @Test
    void rankOnLiveWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), clock, 2);
        w.increment("a", 5);
        w.increment("b", 9);
        assertEquals(OptionalInt.of(1), w.rank(clock.instant(), "b"));
        assertEquals(OptionalInt.of(2), w.rank(clock.instant(), "a"));
        assertEquals(OptionalInt.empty(), w.rank(clock.instant(), "zed"));
    }

    /** Proves a write into a window older than the oldest kept one is refused loudly and changes nothing (retain 2: day 3, day 4, then the clock steps back to day 1). */
    @Test
    void staleWriteIsRefused() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-03T10:00:00Z"));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), clock, 2);
        w.increment("a", 1);
        clock.advance(Duration.ofDays(1));
        w.increment("a", 1);
        clock.set(Instant.parse("2026-03-01T10:00:00Z"));
        assertThrows(StaleWindowException.class, () -> w.increment("a", 1));
        assertEquals(2, w.windowStarts().size());
        assertEquals(1, w.top(Instant.parse("2026-03-03T10:00:00Z"), 5).get(0).score());
    }

    /** Proves one forward clock glitch does not poison later writes: with retain 1, a write at day 30 is kept, and a write after the clock returns to day 1 is refused rather than stored and evicted. */
    @Test
    void forwardJumpKeepsTheNewWindowAndRefusesOldOnes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), clock, 1);
        w.increment("a", 1);
        clock.set(Instant.parse("2026-03-31T10:00:00Z"));
        assertEquals(1, w.increment("a", 1));
        clock.set(Instant.parse("2026-03-01T11:00:00Z"));
        assertThrows(StaleWindowException.class, () -> w.increment("a", 1));
        assertEquals(1, w.top(Instant.parse("2026-03-31T10:00:00Z"), 5).get(0).score());
        assertEquals(1, w.windowStarts().size());
    }

    /** Proves expiry follows the calendar, not the count of written windows: retain 3, writes on day 1 and day 10 leave only day 10; and reads of an expired day are empty even before any new write. */
    @Test
    void expiryIsByCalendarWithSparseActivity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-01T10:00:00Z"));
        WindowedLeaderboard w = new WindowedLeaderboard(Window.DAILY, ZoneId.of("UTC"), clock, 3);
        Instant day1 = clock.instant();
        w.increment("a", 1);
        clock.set(Instant.parse("2026-03-02T10:00:00Z"));
        w.increment("a", 1);
        clock.set(Instant.parse("2026-03-04T10:00:00Z"));
        assertTrue(w.top(day1, 5).isEmpty(), "day 1 is more than 2 calendar days behind day 4");
        w.increment("a", 1);
        assertEquals(List.of(Instant.parse("2026-03-02T00:00:00Z"), Instant.parse("2026-03-04T00:00:00Z")), w.windowStarts());
        clock.set(Instant.parse("2026-03-10T10:00:00Z"));
        w.increment("a", 1);
        assertEquals(List.of(Instant.parse("2026-03-10T00:00:00Z")), w.windowStarts());
    }

    /** Proves the fall-back DST day (2026-11-01 in New York) is 25 hours long, and that the previous window start is found across it. */
    @Test
    void dstFallBackDayIsTwentyFiveHours() {
        Instant noon = Instant.parse("2026-11-01T17:00:00Z");
        Instant start = Window.DAILY.startOf(noon, NY);
        assertEquals(Duration.ofHours(25), Duration.between(start, Window.DAILY.endOf(noon, NY)));
        assertEquals(Instant.parse("2026-10-31T04:00:00Z"), Window.DAILY.previousStart(start, NY));
    }
}
