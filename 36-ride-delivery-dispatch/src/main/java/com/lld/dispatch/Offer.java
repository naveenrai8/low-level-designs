package com.lld.dispatch;

/**
 * A promise-in-waiting: this driver may take this request until the deadline. The id is never reused, so an old id can never be mistaken for a newer offer (that is the "stale offer" defence).
 *
 * @param id the offer id, 1, 2, 3, ... never reused
 * @param driverId the driver being asked
 * @param requestId the request on offer
 * @param expiresAtMs the offer is live while the clock is strictly below this time
 */
public record Offer(long id, long driverId, long requestId, long expiresAtMs) {
}
