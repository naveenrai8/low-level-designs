package com.lld.library;

import java.time.Instant;

/**
 * The message sent to a member when a copy is waiting for them.
 *
 * @param memberId who to tell
 * @param isbn the title that is ready
 * @param copyId the copy kept for them
 * @param branch the branch to collect it from
 * @param expiresAt the end of the pickup window (they lose the copy at exactly this instant)
 */
public record HoldNotice(String memberId, String isbn, String copyId, String branch, Instant expiresAt) {
}
