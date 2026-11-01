package com.lld.library;

/** Where one physical copy is in its life. A copy is in exactly one of these at any moment. */
public enum CopyStatus {
    /** On the shelf of a branch and free to borrow. */
    AVAILABLE,
    /** Out on loan to a member. */
    LOANED,
    /** Kept at a branch for the member at the head of the hold queue, until the pickup window ends. */
    ON_HOLD,
    /** Being moved to another branch. It cannot be borrowed until it is received. */
    IN_TRANSIT
}
