package com.lld.ticketing;

import java.time.Duration;

/**
 * Settings of the checkout.
 *
 * @param venueAccountId the ledger account that receives the captured money
 * @param paymentGrace how much longer the payment hold lives than the seat hold, so the money can never be released before the seats are
 * @param keyRetention how long a finished checkout is remembered for replay before {@link Checkout#purge()} may forget it (an assumption: keep it at least as long as Topic 31 keeps its keys, 24 hours by default)
 */
public record TicketingConfig(String venueAccountId, Duration paymentGrace, Duration keyRetention) {

    /** Rejects a blank venue account and a zero or negative grace or retention. */
    public TicketingConfig {
        if (venueAccountId == null || venueAccountId.isBlank()) {
            throw new IllegalArgumentException("venueAccountId is required");
        }
        for (Duration d : new Duration[] {paymentGrace, keyRetention}) {
            if (d == null || d.isZero() || d.isNegative()) {
                throw new IllegalArgumentException("durations must be positive: " + d);
            }
        }
    }

    /** Returns the settings for a venue account with a 10 minute grace and 24 hour key retention. */
    public static TicketingConfig forVenue(String venueAccountId) {
        return new TicketingConfig(venueAccountId, Duration.ofMinutes(10), Duration.ofHours(24));
    }
}
