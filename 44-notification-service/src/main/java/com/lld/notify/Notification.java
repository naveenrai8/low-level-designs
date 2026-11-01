package com.lld.notify;

import java.time.Instant;

/**
 * An immutable snapshot of one notification. Each change creates a new snapshot, so a reader (for example a GET) never sees a half-updated one.
 *
 * @param id the id handed back at submit time
 * @param seq the submit order (1, 2, 3, ...), used to break ties so equal-time messages go out first come, first served; ids are not ordered
 * @param userId the receiver
 * @param channel the medium
 * @param body the finished text, already escaped for the channel and cut to fit
 * @param priority normal or urgent
 * @param status where it is in its life
 * @param attempts how many times the channel was actually called
 * @param reason why it is deferred, dead or suppressed; empty text while nothing special happened
 * @param nextAttemptAt the earliest time the next try may happen (only meaningful while PENDING)
 */
public record Notification(String id, long seq, String userId, ChannelType channel, String body, Priority priority, Status status, int attempts, String reason, Instant nextAttemptAt) {

    /** Returns a copy with a new status, attempt count, reason and next-attempt time. */
    Notification with(Status newStatus, int newAttempts, String newReason, Instant newNext) {
        return new Notification(id, seq, userId, channel, body, priority, newStatus, newAttempts, newReason, newNext);
    }
}
