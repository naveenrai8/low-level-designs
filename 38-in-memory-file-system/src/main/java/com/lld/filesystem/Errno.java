package com.lld.filesystem;

/**
 * The error kinds this file system can report, named after the POSIX errno values so an interviewer who knows Linux reads them at once.
 * <p>The numbers are the Linux values from {@code errno-base.h} and {@code errno.h} (see the README references). They are only labels here, nothing in the code depends on them.
 */
public enum Errno {
    /** Operation not permitted: for example chmod by someone who is not the owner. */
    EPERM(1),
    /** No such file or directory: a path component is missing. */
    ENOENT(2),
    /** Permission denied: a needed r, w or x bit is missing. */
    EACCES(13),
    /** Busy: the operation touches the root directory, which cannot be removed or renamed. */
    EBUSY(16),
    /** File exists: the name is already taken. */
    EEXIST(17),
    /** Not a directory: a path component, or the target of a directory-only call, is not a directory. */
    ENOTDIR(20),
    /** Is a directory: the call needs a non-directory, for example rm or cat on a directory. */
    EISDIR(21),
    /** File too large: the content would not fit in one Java byte array (about 2 GB). */
    EFBIG(27),
    /** Invalid argument: a bad name, a bad mode, a bad glob, or moving a directory into itself. */
    EINVAL(22),
    /** Directory not empty: rmdir, or a rename that replaces a directory that still has entries. */
    ENOTEMPTY(39),
    /** Too many levels of symbolic links: a link loop, or more than 40 hops. */
    ELOOP(40);

    private final int code;

    /**
     * Remembers the Linux number for this error kind.
     *
     * @param code the Linux errno value
     */
    Errno(int code) {
        this.code = code;
    }

    /**
     * Gives the Linux errno number, handy for printing like a shell would.
     *
     * @return the numeric errno value
     */
    public int code() {
        return code;
    }
}
