package com.lld.pubsub;

/** Where a brand-new subscriber group starts reading. */
public enum StartAt {
    /** From offset 0, so the group sees the whole retained log. */
    EARLIEST,
    /** From the end of the log, so the group sees only messages published after it subscribes. */
    LATEST
}
