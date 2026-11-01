package com.lld.filesystem;

/**
 * A read-only description of one entry, like the output of {@code stat}.
 *
 * @param id     unique node id, never reused; also the global lock order
 * @param kind   file, directory or symlink
 * @param size   bytes of content for a file, 0 for a directory, length of the target text for a symlink
 * @param owner  the owner's name
 * @param mode   permission bits such as {@code 0755}: owner bits are 0700, "other" bits are 0007, the group bits are stored but ignored
 * @param target the link target for a symlink, otherwise null
 */
public record Stat(long id, Kind kind, long size, String owner, int mode, String target) {
}
