package com.lld.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves the quiet-hours window: half-open edges, midnight crossing, zones and both DST traps. */
class QuietHoursTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private final QuietHours night = Fixture.night();

    /** Proves the start is inside and the end is outside (half-open), for a window that crosses midnight. */
    @Test
    void halfOpenEdges() {
        assertTrue(night.contains(LocalTime.of(22, 0)));
        assertFalse(night.contains(LocalTime.of(21, 59, 59)));
        assertTrue(night.contains(LocalTime.of(6, 59, 59)));
        assertFalse(night.contains(LocalTime.of(7, 0)));
        assertTrue(night.contains(LocalTime.MIDNIGHT));
    }

    /** Proves a same-day window (13:00 to 15:00) works too. */
    @Test
    void sameDayWindow() {
        QuietHours q = new QuietHours(LocalTime.of(13, 0), LocalTime.of(15, 0));
        assertTrue(q.contains(LocalTime.of(14, 0)));
        assertFalse(q.contains(LocalTime.of(15, 0)));
        assertFalse(q.contains(LocalTime.of(23, 0)));
    }

    /** Proves start equal to end is refused. */
    @Test
    void equalStartAndEndIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new QuietHours(LocalTime.NOON, LocalTime.NOON));
    }

    /** Proves the end is tomorrow morning when it is evening, and this morning when it is after midnight. */
    @Test
    void endIsTheNextSevenAm() {
        assertEquals(Instant.parse("2026-01-06T07:00:00Z"), night.endAfter(Instant.parse("2026-01-05T23:00:00Z"), ZoneId.of("UTC")).get());
        assertEquals(Instant.parse("2026-01-06T07:00:00Z"), night.endAfter(Instant.parse("2026-01-06T03:00:00Z"), ZoneId.of("UTC")).get());
        assertEquals(Optional.empty(), night.endAfter(Instant.parse("2026-01-06T07:00:00Z"), ZoneId.of("UTC")));
    }

    /** Proves the zone is the user's: the same instant is quiet in Tokyo (23:00) but not in New York (09:00). */
    @Test
    void evaluatedInTheUsersZone() {
        Instant t = Instant.parse("2026-01-05T14:00:00Z");
        assertTrue(night.endAfter(t, ZoneId.of("Asia/Tokyo")).isPresent());
        assertTrue(night.endAfter(t, NY).isEmpty());
    }

    /** Proves the spring-forward gap: the 02:30 end does not exist on 8 March 2026 in New York, so quiet hours end at the jump (07:00Z = 03:00 local), not an hour late or early. */
    @Test
    void springForwardGap() {
        QuietHours q = new QuietHours(LocalTime.of(22, 0), LocalTime.of(2, 30));
        Instant end = q.endAfter(Instant.parse("2026-03-08T04:00:00Z"), NY).get(); // 23:00 on 7 March, local
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), end);
        assertEquals(LocalTime.of(3, 0), end.atZone(NY).toLocalTime());
        assertTrue(q.endAfter(end, NY).isEmpty());
    }

    /** Proves the fall-back overlap: 01:30 happens twice on 1 November 2026. The first pass ends at 05:30Z (EDT), the second pass at 06:30Z (EST), and the answer is never in the past. */
    @Test
    void fallBackOverlap() {
        QuietHours q = new QuietHours(LocalTime.of(22, 0), LocalTime.of(1, 30));
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), q.endAfter(Instant.parse("2026-11-01T03:00:00Z"), NY).get());
        Instant secondPass = Instant.parse("2026-11-01T06:10:00Z"); // 01:10 EST, after the clock went back
        Instant end = q.endAfter(secondPass, NY).get();
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), end);
        assertTrue(end.isAfter(secondPass));
    }
}
