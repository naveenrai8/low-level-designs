package com.lld.library;

import java.time.Duration;
import java.time.ZoneId;

/**
 * The rules a library runs by, in one place.
 *
 * <p>Fine rule: a loan is due {@code loanDays} days after it was lent, at the same wall-clock time of day in {@code zone}. It is late only if the return is strictly after the due instant (late is the open range after due). Days late are counted in 24-hour local periods starting at the due time of day in {@code zone} (so a daylight saving change does not make a day 23 or 25 hours long), rounded up: one second late is one day. The fine is {@code days * finePerDayMinor}, never above {@code fineCapMinor}.
 *
 * @param maxLoans most open loans one member may hold
 * @param loanDays length of a loan in local days
 * @param finePerDayMinor fine per late day in minor units
 * @param fineCapMinor most one loan can ever cost in fines, in minor units
 * @param holdPickup how long a held copy waits for its member
 * @param zone the library's time zone, used for due dates and counting days
 */
public record LibraryPolicy(int maxLoans, int loanDays, long finePerDayMinor, long fineCapMinor, Duration holdPickup, ZoneId zone) {

    /** Rejects numbers that make no sense, so a bad policy fails at start-up and not at the first return. */
    public LibraryPolicy {
        if (maxLoans < 1 || loanDays < 1 || finePerDayMinor < 0 || fineCapMinor < 0) {
            throw new IllegalArgumentException("limits must be positive and money non-negative");
        }
        if (holdPickup == null || holdPickup.isZero() || holdPickup.isNegative() || zone == null) {
            throw new IllegalArgumentException("holdPickup must be positive and zone must be set");
        }
    }

    /** A sensible default: 3 loans, 14 days, 25 per day, capped at 1000, 2 days to collect a hold, UTC. */
    public static LibraryPolicy standard() {
        return new LibraryPolicy(3, 14, 25, 1000, Duration.ofDays(2), java.time.ZoneOffset.UTC);
    }
}
