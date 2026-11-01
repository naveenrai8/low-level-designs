package com.lld.filesystem;

/**
 * What {@code find} should look for. Every filter is optional and filters combine with AND.
 *
 * @param nameGlob       glob matched against the entry's own name only (like {@code find -name}), or null for any name
 * @param minSize        smallest size in bytes, inclusive, or null; setting a size filter keeps regular files only
 * @param maxSize        largest size in bytes, inclusive, or null
 * @param kind           only this kind of entry, or null for any
 * @param followSymlinks true to walk into symlinked directories (loops are skipped); false is the default
 */
public record FindQuery(String nameGlob, Long minSize, Long maxSize, Kind kind, boolean followSymlinks) {
    /**
     * Rejects negative sizes and an empty size range.
     */
    public FindQuery {
        if ((minSize != null && minSize < 0) || (maxSize != null && maxSize < 0)) {
            throw new FsException(Errno.EINVAL, "size filter must not be negative");
        }
        if (minSize != null && maxSize != null && minSize > maxSize) {
            throw new FsException(Errno.EINVAL, "minSize " + minSize + " > maxSize " + maxSize);
        }
    }

    /**
     * Starts a query that matches everything and does not follow symlinks.
     *
     * @return the match-all query
     */
    public static FindQuery all() {
        return new FindQuery(null, null, null, null, false);
    }

    /**
     * Copies this query with a name glob.
     *
     * @param glob the glob, see {@link Glob}
     * @return the new query
     */
    public FindQuery named(String glob) {
        return new FindQuery(glob, minSize, maxSize, kind, followSymlinks);
    }

    /**
     * Copies this query with a lower size bound.
     *
     * @param bytes smallest size, inclusive
     * @return the new query
     */
    public FindQuery atLeast(long bytes) {
        return new FindQuery(nameGlob, bytes, maxSize, kind, followSymlinks);
    }

    /**
     * Copies this query with an upper size bound.
     *
     * @param bytes largest size, inclusive
     * @return the new query
     */
    public FindQuery atMost(long bytes) {
        return new FindQuery(nameGlob, minSize, bytes, kind, followSymlinks);
    }

    /**
     * Copies this query restricted to one kind of entry.
     *
     * @param wanted the kind to keep
     * @return the new query
     */
    public FindQuery ofKind(Kind wanted) {
        return new FindQuery(nameGlob, minSize, maxSize, wanted, followSymlinks);
    }

    /**
     * Copies this query with symlink following switched on or off.
     *
     * @param follow true to descend into symlinked directories
     * @return the new query
     */
    public FindQuery following(boolean follow) {
        return new FindQuery(nameGlob, minSize, maxSize, kind, follow);
    }
}
