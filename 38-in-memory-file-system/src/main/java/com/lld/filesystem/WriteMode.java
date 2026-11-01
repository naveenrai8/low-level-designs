package com.lld.filesystem;

/** Says what {@code write} does when the file already has content. */
public enum WriteMode {
    /** Replace the whole content (like {@code >} in a shell). */
    OVERWRITE,
    /** Add to the end of the existing content (like {@code >>} in a shell). */
    APPEND
}
