package com.lld.filesystem;

/**
 * A test seam that lets a test pause a thread at an exact point, so races are forced instead of hoped for.
 * <p>Production code uses {@link #NONE}, which does nothing.
 */
public interface Hook {
    /** The hook that does nothing. */
    Hook NONE = new Hook() { };

    /**
     * Called after a mutating call has resolved its path and before it takes any lock. A test parks here so two threads both finish their unlocked checks first.
     */
    default void beforeLock() {
    }

    /**
     * Called right after the first directory lock of a call is taken, while it is still held. A test parks here to prove what the second lock acquisition does.
     *
     * @param dirId the id of the directory whose lock was just taken
     */
    default void afterFirstLock(long dirId) {
    }

    /**
     * Called when a mutating call has passed every check and is one step before it changes the tree. The directory locks the call needs are held, except for a write to an existing file, which holds no directory lock here (the file's own lock is taken inside the write). A test parks here to prove two calls can (or cannot) be in this state at the same time.
     */
    default void beforeMutate() {
    }

    /**
     * Called by rename after both paths are resolved and before any lock, including the directory-move lock, is taken. No lock is held here, so a test can make two renames wait for each other with a plain barrier.
     */
    default void afterResolve() {
    }
}
