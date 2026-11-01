package com.lld.library;

/** Test seam that lets a test pause or probe a thread at a named point, to force one exact interleaving. Production code uses {@link #NONE}. */
@FunctionalInterface
public interface Hook {

    /** Right after a thread looked at a copy and before it tries to take it. */
    String BEFORE_CLAIM = "before-claim";

    /** Right after a thread took a copy and before the loan record is written. */
    String AFTER_CLAIM = "after-claim";

    /** Right after a copy was marked in transit. */
    String IN_TRANSIT = "in-transit";

    /** Inside the loan-slot loop, after the member was seen below the limit and before the compare-and-set that takes the slot. The id passed is the member id. */
    String BEFORE_SLOT = "before-slot";

    /** Inside the transfer loop, after the copy was seen on a shelf and before the compare-and-set that moves it. */
    String BEFORE_TRANSFER = "before-transfer";

    /** Called at the named point with the id involved (a copy id, or the member id for {@link #BEFORE_SLOT}). */
    void at(String point, String copyId);

    /** A hook that does nothing. */
    Hook NONE = (point, copyId) -> { };
}
