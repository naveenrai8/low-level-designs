package com.lld.time;

import java.time.Duration;

/** "Has this much time passed since I started?" The question behind every timeout. */
public interface Deadline {

    boolean hasExpired();

    /** Time left, never negative. */
    Duration remaining();
}
