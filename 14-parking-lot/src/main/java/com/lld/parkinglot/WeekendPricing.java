package com.lld.parkinglot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Picks one of two strategies by the day the vehicle <em>entered</em>: Saturday or Sunday uses the weekend strategy, any other day the weekday one.
 *
 * <p>Rule: the entry day decides the whole stay. A stay that starts Friday 23:00 and ends Saturday is a weekday stay. This is simple and predictable; splitting a stay at midnight is a possible extension.
 */
public final class WeekendPricing implements PricingStrategy {

    private final PricingStrategy weekday;
    private final PricingStrategy weekend;
    private final ZoneId zone;

    /** Creates the strategy. The zone says where "Saturday" is, because the same instant is a different day in different places. */
    public WeekendPricing(PricingStrategy weekday, PricingStrategy weekend, ZoneId zone) {
        this.weekday = java.util.Objects.requireNonNull(weekday, "weekday");
        this.weekend = java.util.Objects.requireNonNull(weekend, "weekend");
        this.zone = java.util.Objects.requireNonNull(zone, "zone");
    }

    /** {@inheritDoc} */
    @Override
    public long fee(VehicleType type, Instant entry, Instant exit) {
        DayOfWeek day = entry.atZone(zone).getDayOfWeek();
        boolean isWeekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        return (isWeekend ? weekend : weekday).fee(type, entry, exit);
    }
}
