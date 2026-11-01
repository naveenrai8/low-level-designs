package com.lld.filesystem;

/**
 * The one exception every file system call throws on failure, carrying an {@link Errno} kind.
 * <p>Tests and callers branch on {@link #errno()}, never on the message text. Every call that throws this leaves the tree exactly as it found it.
 */
public final class FsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Errno errno;

    /**
     * Builds the exception for one failed call.
     *
     * @param errno   the error kind
     * @param message a human-readable detail such as the path involved
     */
    public FsException(Errno errno, String message) {
        super(errno + ": " + message);
        this.errno = errno;
    }

    /**
     * Tells which kind of failure this is.
     *
     * @return the POSIX-style error kind
     */
    public Errno errno() {
        return errno;
    }
}
