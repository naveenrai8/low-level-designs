package com.lld.filesystem;

/** What a tree entry is: a regular file, a directory, or a symbolic link (a stored path that points elsewhere). */
public enum Kind {
    /** A regular file that holds bytes. */
    FILE,
    /** A directory that holds named children. */
    DIRECTORY,
    /** A symbolic link: a stored target path, followed when a path is resolved. */
    SYMLINK
}
