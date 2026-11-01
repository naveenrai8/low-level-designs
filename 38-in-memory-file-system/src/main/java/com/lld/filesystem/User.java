package com.lld.filesystem;

/**
 * Who is asking. Permissions are checked against this name.
 * <p>The user called {@code root} is the superuser and skips every permission check, like uid 0 on Linux.
 *
 * @param name the user name, never blank
 */
public record User(String name) {
    /** The superuser. It owns the root directory when the file system is created. */
    public static final User ROOT = new User("root");

    /**
     * Rejects a blank name so every node always has a real owner.
     */
    public User {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("user name must not be blank");
        }
    }

    /**
     * Says whether this user bypasses permission checks.
     *
     * @return true only for the user named {@code root}
     */
    public boolean superuser() {
        return name.equals("root");
    }
}
