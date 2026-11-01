package com.lld.parkinglot;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Charges a rate per started hour.
 *
 * <p>Rounding rule: a partial hour counts as a full hour (round up). So 0 minutes costs 0, 1 nanosecond to exactly 60 minutes costs 1 hour, and 60 minutes plus 1 nanosecond costs 2 hours.
 */
public final class HourlyPricing implements PricingStrategy {

    private static final long NANOS_PER_HOUR = Duration.ofHours(1).toNanos();

    private final Map<VehicleType, Long> perHour;

    /** Creates the pricing from a rate (minor units per hour) for every vehicle type. Missing or negative rates are rejected. */
    public HourlyPricing(Map<VehicleType, Long> perHour) {
        this.perHour = PricingTables.copyComplete(perHour);
    }

    /** {@inheritDoc} */
    @Override
    public long fee(VehicleType type, Instant entry, Instant exit) {
        Duration stay = Duration.between(entry, exit);
        if (stay.isNegative()) {
            throw new IllegalArgumentException("exit is before entry");
        }
        long hours = Math.ceilDiv(stay.toNanos(), NANOS_PER_HOUR);
        return Math.multiplyExact(hours, perHour.get(type));
    }

    /** Small helper shared by the pricing classes. */
    static final class PricingTables {
        /** Hidden: this class only holds a static helper. */
        private PricingTables() {
        }

        /** Copies the rates and checks every vehicle type has a non-negative one. */
        static Map<VehicleType, Long> copyComplete(Map<VehicleType, Long> rates) {
            Map<VehicleType, Long> copy = new EnumMap<>(VehicleType.class);
            for (VehicleType t : VehicleType.values()) {
                Long rate = rates.get(t);
                if (rate == null || rate < 0) {
                    throw new IllegalArgumentException("missing or negative rate for " + t);
                }
                copy.put(t, rate);
            }
            return copy;
        }
    }
}
