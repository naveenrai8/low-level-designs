package com.lld.parkinglot;

import java.time.Instant;

/**
 * How a stay is priced. Swap the strategy to change the price rules without touching the lot.
 *
 * <p>Contract: returns the fee in minor units (never negative). Throws {@link IllegalArgumentException} if {@code exit} is before {@code entry}, because a clock that went backwards must not silently produce a free or negative fee.
 */
@FunctionalInterface
public interface PricingStrategy {

    /** Returns the fee in minor units for a stay of this vehicle type from {@code entry} to {@code exit}. */
    long fee(VehicleType type, Instant entry, Instant exit);
}
