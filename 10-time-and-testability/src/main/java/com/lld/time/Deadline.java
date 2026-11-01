package com.lld.time;

import java.time.Duration;

/**
 * Answers "has this much time passed since I started?", the question behind every timeout.
 */
public interface Deadline {

    /**
     * Says whether the timeout has run out.
     */
    boolean hasExpired();

    /**
     * Returns the time left, never negative.
     */
    Duration remaining();
}
