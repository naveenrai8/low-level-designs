package com.lld.parkinglot;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Charges one fixed price per visit, however long the stay is. */
public final class FlatPricing implements PricingStrategy {

    private final Map<VehicleType, Long> perVisit;

    /** Creates the pricing from a fixed price (minor units) for every vehicle type. */
    public FlatPricing(Map<VehicleType, Long> perVisit) {
        this.perVisit = HourlyPricing.PricingTables.copyComplete(perVisit);
    }

    /** {@inheritDoc} */
    @Override
    public long fee(VehicleType type, Instant entry, Instant exit) {
        if (Duration.between(entry, exit).isNegative()) {
            throw new IllegalArgumentException("exit is before entry");
        }
        return perVisit.get(type);
    }
}
