package com.lld.parkinglot;

import java.time.Instant;

/**
 * A spot held for one vehicle until {@code expiresAt}.
 *
 * <p>The hold runs until {@code expiresAt}, exclusive: it is valid while {@code now < expiresAt} and has already expired at exactly {@code expiresAt}.
 *
 * @param id unique reservation id
 * @param vehicle the vehicle the spot is held for
 * @param spotId the held spot
 * @param expiresAt the first instant at which the hold no longer counts
 */
public record Reservation(String id, Vehicle vehicle, String spotId, Instant expiresAt) {

    /** Says whether the hold still protects the spot at {@code now}: true while {@code now} is strictly before {@code expiresAt}. */
    public boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
