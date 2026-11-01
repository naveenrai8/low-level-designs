package com.lld.filesystem;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * An in-memory POSIX-like file system. Paths are absolute and use {@code /}.
 * <p>Error contract: every failure throws {@link FsException} with an {@link Errno}, and a failed call changes nothing.
 * <p>Path rules (see {@code path_resolution(7)}): repeated and trailing slashes are ignored, {@code .} is the current directory, {@code ..} is the PHYSICAL parent (after a symlink it is the parent of where the link led), and {@code ..} at the root stays at the root. Symlinks in the middle of a path are always followed, at most 40 per call, then {@link Errno#ELOOP}. A symlink in the last position is followed by every call except {@code mkdir}, {@code rm}, {@code rmdir}, {@code rename}, {@code symlink}, {@code readlink} and {@code lstat}. A trailing slash demands a directory: a regular file with a trailing slash is ENOTDIR. A trailing slash does NOT make {@code rm}, {@code rmdir}, {@code rename}, {@code mkdir} or {@code symlink} follow the last link (so {@code rmdir("/link/")} is ENOTDIR and {@code mkdir("/link/")} is EEXIST, as on Linux), but {@code lstat} does follow it (POSIX). A symlink target that ends in a slash must lead to a directory, and a NUL in a target is EINVAL. Permissions are checked before the locks are taken (rename checks again under its locks), so a concurrent chmod can race with a call, as on Linux; the sticky bit, setuid and groups are out of scope. The tree is atomic per directory but not linearizable per path: a call that resolved a directory just before a rename moved it may still create the entry under the directory's new name. A relative path is {@link Errno#EINVAL}; an empty path is {@link Errno#ENOENT}.
 * <p>There are no timestamps, so there is no Clock or Ticker in this design.
 */
public interface FileSystem {
    /**
     * Gives a view of the same tree that acts as another user.
     *
     * @param user who is asking from now on
     * @return a view sharing this tree; permission checks use {@code user}
     */
    FileSystem as(User user);

    /**
     * Creates one directory. Its parent must exist.
     *
     * @param path where to create it
     * @throws FsException ENOENT (parent missing), EEXIST (name taken, even by a dangling symlink), ENOTDIR, EACCES (needs w and x on the parent), EINVAL
     */
    void mkdir(String path);

    /**
     * Lists the names inside a directory in sorted order.
     *
     * @param path a directory, or a symlink to one
     * @return the child names, sorted
     * @throws FsException ENOENT, ENOTDIR (not a directory), EACCES (needs r on the directory)
     */
    List<String> ls(String path);

    /**
     * Reads a whole file.
     *
     * @param path a regular file, or a symlink to one
     * @return a copy of the bytes
     * @throws FsException ENOENT, EISDIR, EACCES (needs r on the file)
     */
    byte[] read(String path);

    /**
     * Reads a whole file as UTF-8 text.
     *
     * @param path a regular file, or a symlink to one
     * @return the content as text
     * @throws FsException the same as {@link #read}
     */
    default String cat(String path) {
        return new String(read(path), StandardCharsets.UTF_8);
    }

    /**
     * Writes bytes to a file, creating it when missing (its parent must exist). A symlink in the last position is followed, so writing through a dangling link creates the link's target.
     *
     * @param path where to write
     * @param data the bytes; copied
     * @param mode OVERWRITE replaces the content, APPEND adds to the end atomically
     * @throws FsException ENOENT, ENOTDIR, EISDIR (a directory, or a trailing slash on a new name), EACCES (w on the file, or w and x on the parent when creating)
     */
    void write(String path, byte[] data, WriteMode mode);

    /**
     * Writes UTF-8 text to a file.
     *
     * @param path    where to write
     * @param content the text
     * @param mode    OVERWRITE or APPEND
     * @throws FsException the same as {@link #write(String, byte[], WriteMode)}
     */
    default void write(String path, String content, WriteMode mode) {
        write(path, content.getBytes(StandardCharsets.UTF_8), mode);
    }

    /**
     * Removes a file or a symlink. A symlink is removed itself; its target is untouched.
     *
     * @param path the entry to remove
     * @throws FsException ENOENT, EISDIR (a directory; use rmdir), ENOTDIR (trailing slash), EACCES (w and x on the parent), EBUSY (root), EINVAL (ends in . or ..)
     */
    void rm(String path);

    /**
     * Removes an empty directory.
     *
     * @param path the directory
     * @throws FsException ENOENT, ENOTDIR, ENOTEMPTY, EACCES (w and x on the parent), EBUSY (root), EINVAL (ends in . or ..)
     */
    void rmdir(String path);

    /**
     * Moves or renames an entry, replacing the destination when allowed. A symlink is moved itself, not followed. A file may replace a file; a directory may replace an EMPTY directory; mixing the two is EISDIR or ENOTDIR. Moving a directory into its own subtree is EINVAL. Renaming an entry onto itself does nothing.
     *
     * @param from the existing entry
     * @param to   the new path; its parent must exist
     * @throws FsException ENOENT, EINVAL, EISDIR, ENOTDIR, ENOTEMPTY, EACCES (w and x on both parents, and w on a directory that changes parent), EBUSY (root)
     */
    void rename(String from, String to);

    /**
     * Creates a symbolic link at {@code linkPath} that stores {@code target} as plain text. A relative target is resolved from the directory that holds the link.
     *
     * @param target   where the link points; not checked for existence
     * @param linkPath where to create the link
     * @throws FsException EINVAL (empty target or NUL), EEXIST (also a trailing slash on an existing name), ENOENT (trailing slash on a new name), ENOTDIR, EACCES
     */
    void symlink(String target, String linkPath);

    /**
     * Reads the stored target of a symlink without following it.
     *
     * @param path the symlink
     * @return the target text
     * @throws FsException ENOENT, EINVAL (not a symlink)
     */
    String readlink(String path);

    /**
     * Changes permission bits. Only the owner or the superuser may.
     *
     * @param path the entry; a symlink is followed
     * @param mode bits between 0 and 0777
     * @throws FsException ENOENT, EINVAL (bad mode), EPERM (not the owner)
     */
    void chmod(String path, int mode);

    /**
     * Describes an entry, following a symlink in the last position.
     *
     * @param path the entry
     * @return its description
     * @throws FsException ENOENT, ENOTDIR, EACCES (x on each directory), ELOOP
     */
    Stat stat(String path);

    /**
     * Describes an entry without following a symlink in the last position.
     *
     * @param path the entry
     * @return its description
     * @throws FsException the same as {@link #stat}
     */
    Stat lstat(String path);

    /**
     * Walks a tree and returns the paths that match, parents before children, children in name order. Directories the caller cannot read and search (r and x) are skipped silently; real find would print EACCES and carry on. The start path is followed if it is a symlink (like {@code find -H}), links below it are not unless the query asks (like {@code find -P}); with following on, a directory already on the walk path is skipped without a message where {@code find -L} prints a loop diagnostic, and a directory reachable by two links is listed twice as {@code find -L} does. {@code *} and {@code ?} match a leading dot, as in {@code find -name} (IEEE PASC interpretation 126, quoted in find(1)). The walk is weakly consistent: it sees each directory as it was when that directory was read.
     *
     * @param start where to begin; a symlink is followed
     * @param query the filters
     * @return matching paths, each built from {@code start} plus the names walked
     * @throws FsException ENOENT, EINVAL (bad glob), EACCES, ELOOP for the start path
     */
    List<String> find(String start, FindQuery query);

    /**
     * Dumps the whole tree as sorted text lines (path, kind, id, owner, mode, size, target, live flag, file content as hex), ignoring permissions. Meant for tests: two equal snapshots mean nothing in the tree changed. It locks one directory at a time, so call it when no writer is running.
     *
     * @return one line per entry
     */
    List<String> snapshot();
}
