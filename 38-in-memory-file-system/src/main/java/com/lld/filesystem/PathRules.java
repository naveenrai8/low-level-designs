package com.lld.filesystem;

import java.util.ArrayDeque;
import java.util.Deque;

/** Small rules about names and path text that do not need the tree. */
public final class PathRules {
    /**
     * Not meant to be created.
     */
    private PathRules() {
    }

    /**
     * Checks that a name may be given to a new entry: not empty, not {@code .} or {@code ..}, no {@code /}, no NUL character.
     * <p>Why: {@code .} and {@code ..} are navigation words, and a {@code /} inside a name could never be reached again by a path.
     *
     * @param name the proposed name
     * @throws FsException with {@link Errno#EINVAL} when the name is not allowed
     */
    public static void validateName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
            throw new FsException(Errno.EINVAL, "invalid name '" + name + "'");
        }
    }

    /**
     * Splits path text on {@code /} into its components, dropping empty pieces so repeated and trailing slashes vanish.
     * {@code .} and {@code ..} are kept because they depend on the tree.
     *
     * @param path any path text
     * @return the components in order, in a deque the caller may push more onto
     */
    static Deque<String> components(String path) {
        Deque<String> out = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.addLast(part);
            }
        }
        return out;
    }
}
