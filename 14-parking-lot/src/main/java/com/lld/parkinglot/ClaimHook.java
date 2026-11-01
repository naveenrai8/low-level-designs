package com.lld.parkinglot;

/**
 * Test seams that let a test pause a gate in the gap between looking and taking, to force a chosen interleaving.
 */
@FunctionalInterface
public interface ClaimHook {

    /** Called once per candidate spot, with that spot's id, right before the take attempt. Production code uses {@link #NONE}. */
    void beforeClaim(String spotId);

    /** Called by {@code parkReserved} after it has checked the hold is still valid and just before it takes the spot. Does nothing by default. */
    default void afterHoldCheck(String reservationId) {
    }

    /** A hook that does nothing. */
    ClaimHook NONE = spotId -> { };
}
