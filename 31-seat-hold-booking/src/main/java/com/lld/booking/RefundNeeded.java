package com.lld.booking;

import java.time.Instant;

/**
 * A note that money arrived for a hold that had already expired, so somebody must give it back. It is the "late payment callback" outcome.
 *
 * @param idempotencyKey the key of the confirm call (one note per key, however often the caller retries)
 * @param holdId the expired hold
 * @param paymentRef the payment that must be refunded
 * @param recordedAt when the late callback was noticed
 */
public record RefundNeeded(String idempotencyKey, String holdId, String paymentRef, Instant recordedAt) {
}
