package com.lld.filesystem;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One entry in the tree. A directory, a file and a symlink are the three subclasses (the Composite pattern: a directory holds nodes).
 * <p>Every node has its own lock. A directory's lock guards its child map; a file's lock guards its bytes. The {@code id} is never reused and is the global lock order when two locks are needed.
 */
abstract sealed class Node permits Node.Dir, Node.Regular, Node.Link {
    final long id;
    final String owner;
    volatile int mode;
    final ReentrantLock lock = new ReentrantLock();

    /**
     * Sets the fields every node has.
     *
     * @param id    unique id
     * @param owner owner name
     * @param mode  permission bits
     */
    Node(long id, String owner, int mode) {
        this.id = id;
        this.owner = owner;
        this.mode = mode;
    }

    /**
     * Gives the kind of this node.
     *
     * @return FILE, DIRECTORY or SYMLINK
     */
    abstract Kind kind();

    /**
     * Gives the size in bytes: content length for a file, 0 for a directory, target text length for a symlink.
     *
     * @return the size
     */
    abstract long size();

    /** A directory: a sorted map of child name to node. */
    static final class Dir extends Node {
        private final Map<String, Node> children = new TreeMap<>();
        volatile Dir parent;
        volatile boolean live = true;

        /**
         * Creates an empty directory.
         *
         * @param id     unique id
         * @param owner  owner name
         * @param mode   permission bits
         * @param parent the directory it is created in, or null for the root
         */
        Dir(long id, String owner, int mode, Dir parent) {
            super(id, owner, mode);
            this.parent = parent;
        }

        /** Gives the kind of this node. */
        @Override
        Kind kind() {
            return Kind.DIRECTORY;
        }

        /** Gives the size in bytes of this node. */
        @Override
        long size() {
            return 0;
        }

        /**
         * Looks up one child under this directory's lock.
         *
         * @param name the child name
         * @return the child, or null
         */
        Node get(String name) {
            lock.lock();
            try {
                return children.get(name);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Copies the child map under this directory's lock so the caller can walk it without holding the lock.
         *
         * @return a sorted copy of name to child
         */
        Map<String, Node> copy() {
            lock.lock();
            try {
                return new TreeMap<>(children);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Reads a child without locking. The caller must already hold this directory's lock.
         *
         * @param name the child name
         * @return the child, or null
         */
        Node getLocked(String name) {
            return children.get(name);
        }

        /**
         * Adds or replaces a child. The caller must already hold this directory's lock.
         *
         * @param name  the child name
         * @param child the node to store
         */
        void putLocked(String name, Node child) {
            children.put(name, child);
        }

        /**
         * Removes a child. The caller must already hold this directory's lock.
         *
         * @param name the child name
         */
        void removeLocked(String name) {
            children.remove(name);
        }

        /**
         * Tells whether there are no children. The caller must already hold this directory's lock.
         *
         * @return true when empty
         */
        boolean emptyLocked() {
            return children.isEmpty();
        }
    }

    /** A regular file: a byte array guarded by the node's lock. */
    static final class Regular extends Node {
        private byte[] data = new byte[0];

        /**
         * Creates an empty file.
         *
         * @param id    unique id
         * @param owner owner name
         * @param mode  permission bits
         */
        Regular(long id, String owner, int mode) {
            super(id, owner, mode);
        }

        /** Gives the kind of this node. */
        @Override
        Kind kind() {
            return Kind.FILE;
        }

        /** Gives the size in bytes of this node. */
        @Override
        long size() {
            lock.lock();
            try {
                return data.length;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Copies the content under the file's lock.
         *
         * @return a fresh array
         */
        byte[] read() {
            lock.lock();
            try {
                return data.clone();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Replaces or extends the content under the file's lock.
         *
         * @param bytes  the bytes to store; copied
         * @param append true to add to the end, false to replace
         * @throws FsException EFBIG when the result would not fit in a byte array
         */
        void write(byte[] bytes, boolean append) {
            lock.lock();
            try {
                if (!append) {
                    data = bytes.clone();
                    return;
                }
                long total = (long) data.length + bytes.length;
                if (total > Integer.MAX_VALUE - 8) {
                    throw new FsException(Errno.EFBIG, "file would be " + total + " bytes");
                }
                byte[] next = new byte[(int) total];
                System.arraycopy(data, 0, next, 0, data.length);
                System.arraycopy(bytes, 0, next, data.length, bytes.length);
                data = next;
            } finally {
                lock.unlock();
            }
        }
    }

    /** A symbolic link: an immutable target text. */
    static final class Link extends Node {
        final String target;

        /**
         * Creates a link.
         *
         * @param id     unique id
         * @param owner  owner name
         * @param target the stored path text
         */
        Link(long id, String owner, String target) {
            super(id, owner, 0777);
            this.target = target;
        }

        /** Gives the kind of this node. */
        @Override
        Kind kind() {
            return Kind.SYMLINK;
        }

        /** Gives the size in bytes of this node. */
        @Override
        long size() {
            return target.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }
}
