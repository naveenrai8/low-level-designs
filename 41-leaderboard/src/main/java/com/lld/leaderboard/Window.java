package com.lld.leaderboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * The calendar size of a time-windowed board.
 *
 * <p>A window is half-open: it contains its start instant and not its end instant. The end of one window is the start of the next, so an instant exactly on the boundary belongs to the NEXT window and no instant is in two windows or in none.
 *
 * <p>Boundaries are local midnights in the board's time zone, so a day on a daylight-saving change is 23 or 25 hours long. Weekly windows start on Monday.
 */
public enum Window {
    /** One local calendar day. */
    DAILY,
    /** One local calendar week, Monday to Sunday. */
    WEEKLY;

    /** Returns the first instant of the window that contains {@code at}, in zone {@code zone}. */
    public Instant startOf(Instant at, ZoneId zone) {
        return firstDay(at, zone).atStartOfDay(zone).toInstant();
    }

    /** Returns the first instant of the NEXT window, which is the exclusive end of the window that contains {@code at}. */
    public Instant endOf(Instant at, ZoneId zone) {
        LocalDate first = firstDay(at, zone);
        return first.plusDays(this == DAILY ? 1 : 7).atStartOfDay(zone).toInstant();
    }

    /** Returns the start of the window just before the one that begins at {@code start}. Done by stepping 1 ms back across the boundary, which lands in the previous local window even on a 23 or 25 hour day. */
    public Instant previousStart(Instant start, ZoneId zone) {
        return startOf(start.minusMillis(1), zone);
    }

    /** Returns the local date on which the window containing {@code at} begins. */
    private LocalDate firstDay(Instant at, ZoneId zone) {
        LocalDate day = at.atZone(zone).toLocalDate();
        return this == DAILY ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
