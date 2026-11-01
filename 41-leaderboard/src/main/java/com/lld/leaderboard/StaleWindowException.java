package com.lld.leaderboard;

/**
 * Thrown when a score is written into a window that is already older than the windows the board keeps.
 *
 * <p>This usually means the clock stepped backwards (or a forward jump already pushed the board ahead). The write is refused instead of being stored and instantly evicted, so a caller never gets a success for a score that is gone.
 */
public final class StaleWindowException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message naming the refused window and the oldest window still kept. */
    public StaleWindowException(String message) {
        super(message);
    }
}
