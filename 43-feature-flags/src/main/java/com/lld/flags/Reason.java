package com.lld.flags;

/**
 * Why an evaluation gave the variant it did. One value per level of the precedence ladder.
 */
public enum Reason {
    /** The flag's kill switch is off, so the off variant was served. */
    OFF,
    /** A prerequisite flag did not serve the required variant, so the off variant was served. */
    PREREQUISITE_FAILED,
    /** The user is in the flag's individual target list. */
    TARGET,
    /** A targeting rule matched. */
    RULE,
    /** The user's bucket fell into a rollout slice. */
    ROLLOUT,
    /** Nothing else applied, so the default variant was served. */
    DEFAULT,
    /** There is no such flag, so the caller's fallback was returned. */
    FLAG_NOT_FOUND
}
