package com.lld.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A daily "do not disturb" window in the user's own time zone, for example 22:00 to 07:00.
 *
 * <p>The window is <b>half-open</b>: the start is inside, the end is outside. At exactly 07:00 the user is no longer in quiet hours. If the start is later than the end, the window crosses midnight.
 *
 * <p>Everything is decided by the <em>local clock time at that instant</em>, so daylight saving needs no special case for "is it quiet now". It only matters when we ask "when does it end":
 * <ul>
 *   <li><b>Gap (spring forward):</b> an end time like 02:30 does not exist that day, because the clock jumps from 02:00 to 03:00. The window ends at the jump itself, the first instant when the local time is 02:30 or later.</li>
 *   <li><b>Overlap (fall back):</b> a time like 01:30 happens twice. We pick the occurrence that matches the offset the user is on right now, so the answer is never in the past.</li>
 * </ul>
 *
 * @param start the first local time inside the window
 * @param end the first local time after the window
 */
public record QuietHours(LocalTime start, LocalTime end) {

    /**
     * Rejects a null time, and a window where start equals end (is that none of the day or all of it? we refuse to guess).
     *
     * @throws IllegalArgumentException if start equals end
     */
    public QuietHours {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new IllegalArgumentException("start and end must differ: " + start);
        }
    }

    /** Returns true if the window runs past midnight (start is later in the day than end). */
    public boolean crossesMidnight() {
        return start.isAfter(end);
    }

    /** Returns true if this local time is inside the window: start included, end excluded. */
    public boolean contains(LocalTime t) {
        if (crossesMidnight()) {
            return !t.isBefore(start) || t.isBefore(end);
        }
        return !t.isBefore(start) && t.isBefore(end);
    }

    /**
     * If {@code now} is inside quiet hours for a user in {@code zone}, returns the instant when quiet hours end. Otherwise returns empty.
     *
     * <p>The returned instant is always after {@code now}. It is almost always outside quiet hours too, but a rare zone change (a gap longer than the time to the next start, such as Pacific/Apia on 2011-12-30) can land inside another window, so {@code runDue} checks again when it gets there.
     */
    public Optional<Instant> endAfter(Instant now, ZoneId zone) {
        ZonedDateTime z = now.atZone(zone);
        LocalTime t = z.toLocalTime();
        if (!contains(t)) {
            return Optional.empty();
        }
        LocalDate day = z.toLocalDate();
        if (crossesMidnight() && !t.isBefore(start)) {
            day = day.plusDays(1); // we are in the evening part, so the end is tomorrow morning
        }
        return Optional.of(instantAt(day.atTime(end), zone, z));
    }

    /** Turns a local end time into an instant, handling the DST gap (use the jump instant) and overlap (prefer the offset we are on now). */
    private static Instant instantAt(LocalDateTime local, ZoneId zone, ZonedDateTime now) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> valid = rules.getValidOffsets(local);
        if (valid.isEmpty()) {
            return rules.getTransition(local).getInstant();
        }
        return ZonedDateTime.ofLocal(local, zone, now.getOffset()).toInstant();
    }
}
