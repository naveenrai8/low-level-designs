package com.lld.library;

import java.time.Instant;

/**
 * The full state of one copy as one immutable value, so a single compare-and-set swaps status, branch, member and deadline together.
 *
 * <p>Meaning of the fields by status: AVAILABLE has only a branch. LOANED has the branch it was lent from and the borrower. ON_HOLD has the branch, the member it waits for and the pickup deadline. IN_TRANSIT has the destination branch.
 *
 * @param status where the copy is in its life
 * @param branch the branch it sits at (or is heading to when in transit)
 * @param member the borrower or the member the copy waits for, else null
 * @param until the end of the pickup window for ON_HOLD, else null
 */
public record CopyState(CopyStatus status, String branch, String member, Instant until) {

    /** A copy on the shelf of the given branch. */
    static CopyState available(String branch) {
        return new CopyState(CopyStatus.AVAILABLE, branch, null, null);
    }

    /** A copy lent to the given member from the given branch. */
    static CopyState loaned(String branch, String member) {
        return new CopyState(CopyStatus.LOANED, branch, member, null);
    }

    /** A copy kept for the given member until the given instant (the member may still collect it before that instant). */
    static CopyState onHold(String branch, String member, Instant until) {
        return new CopyState(CopyStatus.ON_HOLD, branch, member, until);
    }

    /** A copy travelling to the given destination branch. */
    static CopyState inTransit(String destination) {
        return new CopyState(CopyStatus.IN_TRANSIT, destination, null, null);
    }
}
