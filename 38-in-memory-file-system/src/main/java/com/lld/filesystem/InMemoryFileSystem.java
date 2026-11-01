package com.lld.filesystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The file system. One tree, many {@link #as(User) views} (one per user) that share it.
 * <p>Locking: every directory has its own lock. A call takes the lock of each directory it changes, never more than three (two parents and a replaced directory), always in ascending node id. A cross-directory move of a DIRECTORY also takes one tree-wide {@code renameLock} first, because two moves with disjoint lock sets could otherwise each look safe and together create a loop. Path lookup takes one directory lock at a time and releases it, so a call can never hold a lock while waiting for another except in that fixed order. See Topic 08 for lock ordering.
 */
public final class InMemoryFileSystem implements FileSystem {
    /** Linux follows at most 40 symlinks while resolving one path, see path_resolution(7). */
    static final int MAX_LINKS = 40;

    /** What a path walk found: the directory holding the last name, the name, and the node (null when missing). */
    private record Loc(Node.Dir parent, String name, Node node, boolean trailing, boolean special) {
    }

    /** The state shared by every view of one file system. */
    private static final class Tree {
        final AtomicLong ids = new AtomicLong();
        final Node.Dir root = new Node.Dir(ids.getAndIncrement(), "root", 0755, null);
        final ReentrantLock renameLock = new ReentrantLock();
        final Hook hook;

        /**
         * Creates the shared state.
         *
         * @param hook the test seam
         */
        Tree(Hook hook) {
            this.hook = hook;
        }
    }

    private final Tree tree;
    private final User user;

    /**
     * Creates an empty file system acting as the superuser.
     */
    public InMemoryFileSystem() {
        this(Hook.NONE);
    }

    /**
     * Creates an empty file system acting as the superuser, with a test hook.
     *
     * @param hook the test seam; use {@link Hook#NONE} in production
     */
    public InMemoryFileSystem(Hook hook) {
        this.tree = new Tree(hook);
        this.user = User.ROOT;
    }

    /**
     * Creates a view of an existing tree for another user.
     *
     * @param tree the shared state
     * @param user the acting user
     */
    private InMemoryFileSystem(Tree tree, User user) {
        this.tree = tree;
        this.user = user;
    }

    /** Returns a view of the same tree that acts as another user; see {@link FileSystem#as}. */
    @Override
    public FileSystem as(User other) {
        return new InMemoryFileSystem(tree, other);
    }

    // ---------------------------------------------------------------- permissions

    /**
     * Throws EACCES unless the acting user has the given permission bit on the node. The superuser always passes; the owner uses the owner bits; everybody else uses the "other" bits.
     *
     * @param n   the node
     * @param bit 4 for read, 2 for write, 1 for search/execute
     */
    private void need(Node n, int bit) {
        if (user.superuser()) {
            return;
        }
        int shift = n.owner.equals(user.name()) ? 6 : 0;
        if (((n.mode >> shift) & bit) == 0) {
            throw new FsException(Errno.EACCES, "permission denied for " + user.name() + " on node " + n.id);
        }
    }

    /**
     * Tells whether the acting user has the permission bit, without throwing.
     *
     * @param n   the node
     * @param bit 4, 2 or 1
     * @return true when allowed
     */
    private boolean can(Node n, int bit) {
        try {
            need(n, bit);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- path resolution

    /**
     * Rejects paths that are not absolute, because this file system has no current directory.
     *
     * @param path the path text
     * @return the same path
     */
    private static String absolute(String path) {
        if (path == null || path.isEmpty()) {
            throw new FsException(Errno.ENOENT, "empty path");
        }
        if (path.charAt(0) != '/') {
            throw new FsException(Errno.EINVAL, "path must be absolute: " + path);
        }
        return path;
    }

    /**
     * Resolves a path component by component, checking search (x) permission on every directory it passes through.
     * <p>{@code .} stays, {@code ..} goes to the physical parent (root stays root), a symlink in the middle is always followed, the last one only when asked. A trailing slash does NOT make a call follow a last-position link: the call then sees the link itself (so {@code rmdir} and {@code rename} give ENOTDIR and {@code mkdir} gives EEXIST, as on Linux), except {@code lstat}, which follows it as POSIX says. The hop counter is shared with every link followed so the total stays bounded by 40.
     *
     * @param start      directory where a relative path begins (the root for user paths)
     * @param path       the path text
     * @param followLast whether a symlink in the last position is followed
     * @param hops       one-element counter of links followed so far
     * @return where the path ends
     */
    private Loc walk(Node.Dir start, String path, boolean followLast, int[] hops) {
        Deque<String> todo = PathRules.components(path);
        boolean trailing = path.endsWith("/") && !todo.isEmpty();
        Node.Dir cur = start;
        while (!todo.isEmpty()) {
            String comp = todo.pollFirst();
            boolean last = todo.isEmpty();
            need(cur, 1);
            if (comp.equals(".")) {
                continue;
            }
            if (comp.equals("..")) {
                Node.Dir up = cur.parent;
                cur = up == null ? cur : up;
                continue;
            }
            Node child = cur.get(comp);
            if (child == null) {
                if (last) {
                    return new Loc(cur, comp, null, trailing, false);
                }
                throw new FsException(Errno.ENOENT, "no such directory '" + comp + "' in " + path);
            }
            if (child instanceof Node.Link link && (!last || followLast)) {
                if (++hops[0] > MAX_LINKS) {
                    throw new FsException(Errno.ELOOP, "more than " + MAX_LINKS + " symlinks while resolving " + path);
                }
                if (last && link.target.endsWith("/")) {
                    trailing = true;
                }
                Deque<String> target = PathRules.components(link.target);
                if (link.target.startsWith("/")) {
                    cur = tree.root;
                }
                while (!target.isEmpty()) {
                    todo.addFirst(target.pollLast());
                }
                continue;
            }
            if (last) {
                if (trailing && followLast && !(child instanceof Node.Dir)) {
                    throw new FsException(Errno.ENOTDIR, "'" + comp + "' is not a directory");
                }
                return new Loc(cur, comp, child, trailing, false);
            }
            if (!(child instanceof Node.Dir dir)) {
                throw new FsException(Errno.ENOTDIR, "'" + comp + "' is not a directory");
            }
            cur = dir;
        }
        return new Loc(null, null, cur, trailing, true);
    }

    /**
     * Resolves a user-supplied absolute path from the root with a fresh hop budget.
     *
     * @param path       the path
     * @param followLast whether to follow a trailing symlink
     * @return where it ends
     */
    private Loc resolve(String path, boolean followLast) {
        return walk(tree.root, absolute(path), followLast, new int[1]);
    }

    // ---------------------------------------------------------------- locking

    /**
     * Takes the given locks in ascending node id, skipping duplicates, and calls the test hooks.
     * <p>Why ascending id: two calls that need the same two locks then always ask in the same order, so neither can hold one while waiting for the other.
     *
     * @param nodes the nodes to lock (nulls ignored)
     * @return the nodes actually locked, in lock order; pass to {@link #unlockAll}
     */
    private List<Node> lockAll(Node... nodes) {
        tree.hook.beforeLock();
        List<Node> sorted = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Node n : nodes) {
            if (n != null && seen.add(n.id)) {
                sorted.add(n);
            }
        }
        sorted.sort(Comparator.comparingLong(n -> n.id));
        boolean first = true;
        for (Node n : sorted) {
            n.lock.lock();
            if (first) {
                first = false;
                tree.hook.afterFirstLock(n.id);
            }
        }
        return sorted;
    }

    /**
     * Releases locks taken by {@link #lockAll} in the opposite order.
     *
     * @param locked the list returned by lockAll
     */
    private static void unlockAll(List<Node> locked) {
        for (int i = locked.size() - 1; i >= 0; i--) {
            locked.get(i).lock.unlock();
        }
    }

    // ---------------------------------------------------------------- mutations

    /** Creates one directory under its parent holding that parent's lock; fails with EEXIST if the name is taken. */
    @Override
    public void mkdir(String path) {
        Loc loc = resolve(path, false);
        if (loc.special() || loc.node() != null) {
            throw new FsException(Errno.EEXIST, path);
        }
        PathRules.validateName(loc.name());
        need(loc.parent(), 2);
        List<Node> held = lockAll(loc.parent());
        try {
            if (!loc.parent().live) {
                throw new FsException(Errno.ENOENT, "parent was removed: " + path);
            }
            if (loc.parent().getLocked(loc.name()) != null) {
                throw new FsException(Errno.EEXIST, path);
            }
            tree.hook.beforeMutate();
            loc.parent().putLocked(loc.name(), new Node.Dir(tree.ids.getAndIncrement(), user.name(), 0755, loc.parent()));
        } finally {
            unlockAll(held);
        }
    }

    /** Overwrites or appends to a file, creating it under the parent's lock when missing; if another thread creates the name first, the call goes round again and writes to their file. */
    @Override
    public void write(String path, byte[] data, WriteMode mode) {
        while (true) {
            Loc loc = resolve(path, true);
            if (loc.special() || loc.node() instanceof Node.Dir) {
                throw new FsException(Errno.EISDIR, path);
            }
            if (loc.node() instanceof Node.Regular file) {
                need(file, 2);
                tree.hook.beforeMutate();
                file.write(data, mode == WriteMode.APPEND);
                return;
            }
            if (loc.trailing()) {
                throw new FsException(Errno.EISDIR, path);
            }
            PathRules.validateName(loc.name());
            need(loc.parent(), 2);
            List<Node> held = lockAll(loc.parent());
            try {
                if (!loc.parent().live) {
                    throw new FsException(Errno.ENOENT, "parent was removed: " + path);
                }
                if (loc.parent().getLocked(loc.name()) == null) {
                    tree.hook.beforeMutate();
                    Node.Regular created = new Node.Regular(tree.ids.getAndIncrement(), user.name(), 0644);
                    created.write(data, false);
                    loc.parent().putLocked(loc.name(), created);
                    return;
                }
            } finally {
                unlockAll(held);
            }
            // someone created the name between our lookup and our lock: go round again and write to their file
        }
    }

    /** Removes a file or symlink (the link itself, never its target) under the parent's lock; re-checks the name under the lock because it may have changed. */
    @Override
    public void rm(String path) {
        while (true) {
            Loc loc = resolve(path, false);
            if (loc.special()) {
                throw new FsException(loc.node() == tree.root ? Errno.EBUSY : Errno.EINVAL, path);
            }
            if (loc.node() == null) {
                throw new FsException(Errno.ENOENT, path);
            }
            if (loc.node() instanceof Node.Dir) {
                throw new FsException(Errno.EISDIR, path);
            }
            if (loc.trailing()) {
                throw new FsException(Errno.ENOTDIR, path);
            }
            need(loc.parent(), 2);
            List<Node> held = lockAll(loc.parent());
            try {
                if (loc.parent().getLocked(loc.name()) != loc.node()) {
                    continue;
                }
                tree.hook.beforeMutate();
                loc.parent().removeLocked(loc.name());
                return;
            } finally {
                unlockAll(held);
            }
        }
    }

    /** Removes an empty directory, locking the parent and the directory in id order, and marks it dead. */
    @Override
    public void rmdir(String path) {
        while (true) {
            Loc loc = resolve(path, false);
            if (loc.special()) {
                throw new FsException(loc.node() == tree.root ? Errno.EBUSY : Errno.EINVAL, path);
            }
            if (loc.node() == null) {
                throw new FsException(Errno.ENOENT, path);
            }
            if (!(loc.node() instanceof Node.Dir dir)) {
                throw new FsException(Errno.ENOTDIR, path);
            }
            need(loc.parent(), 2);
            List<Node> held = lockAll(loc.parent(), dir);
            try {
                if (loc.parent().getLocked(loc.name()) != dir) {
                    continue;
                }
                if (!dir.emptyLocked()) {
                    throw new FsException(Errno.ENOTEMPTY, path);
                }
                tree.hook.beforeMutate();
                dir.live = false;
                loc.parent().removeLocked(loc.name());
                return;
            } finally {
                unlockAll(held);
            }
        }
    }

    /** Moves an entry; takes both parent locks (and a replaced directory) in ascending id order, plus the tree-wide lock for directory moves. */
    @Override
    public void rename(String from, String to) {
        while (true) {
            Loc src = resolve(from, false);
            Loc dst = resolve(to, false);
            if (src.special() || dst.special()) {
                boolean root = (src.special() && src.node() == tree.root) || (dst.special() && dst.node() == tree.root);
                throw new FsException(root ? Errno.EBUSY : Errno.EINVAL, from + " -> " + to);
            }
            if (src.node() == null) {
                throw new FsException(Errno.ENOENT, from);
            }
            PathRules.validateName(dst.name());
            if (src.trailing() && !(src.node() instanceof Node.Dir)) {
                throw new FsException(Errno.ENOTDIR, from);
            }
            if (dst.trailing() && !(src.node() instanceof Node.Dir)) {
                throw new FsException(Errno.ENOTDIR, to);
            }
            if (src.node() == dst.node()) {
                return;
            }
            tree.hook.afterResolve();
            boolean dirMove = src.node() instanceof Node.Dir && src.parent() != dst.parent();
            if (dirMove) {
                tree.renameLock.lock();
            }
            try {
                Node dstNode = dst.node();
                List<Node> held = lockAll(src.parent(), dst.parent(), dstNode instanceof Node.Dir ? dstNode : null);
                try {
                    if (src.parent().getLocked(src.name()) != src.node() || dst.parent().getLocked(dst.name()) != dstNode) {
                        continue;
                    }
                    // a dead source parent cannot get here: it is empty, so the check above already failed
                    if (!dst.parent().live) {
                        throw new FsException(Errno.ENOENT, "the destination directory was removed");
                    }
                    need(src.parent(), 2);
                    need(dst.parent(), 2);
                    if (dirMove) {
                        // rename(2) EACCES: moving a directory to a new parent rewrites its ".." entry, so it needs w itself
                        need(src.node(), 2);
                    }
                    if (src.node() instanceof Node.Dir moving) {
                        for (Node.Dir up = dst.parent(); up != null; up = up.parent) {
                            if (up == moving) {
                                throw new FsException(Errno.EINVAL, "cannot move " + from + " into itself: " + to);
                            }
                        }
                    }
                    if (dstNode != null) {
                        boolean srcDir = src.node() instanceof Node.Dir;
                        if (srcDir && !(dstNode instanceof Node.Dir)) {
                            throw new FsException(Errno.ENOTDIR, to);
                        }
                        if (!srcDir && dstNode instanceof Node.Dir) {
                            throw new FsException(Errno.EISDIR, to);
                        }
                        if (dstNode instanceof Node.Dir d && !d.emptyLocked()) {
                            throw new FsException(Errno.ENOTEMPTY, to);
                        }
                    }
                    tree.hook.beforeMutate();
                    if (dstNode instanceof Node.Dir d) {
                        d.live = false;
                    }
                    src.parent().removeLocked(src.name());
                    dst.parent().putLocked(dst.name(), src.node());
                    if (src.node() instanceof Node.Dir moved) {
                        moved.parent = dst.parent();
                    }
                    return;
                } finally {
                    unlockAll(held);
                }
            } finally {
                if (dirMove) {
                    tree.renameLock.unlock();
                }
            }
        }
    }

    /** Creates a symlink entry under the parent's lock without checking that the target exists. */
    @Override
    public void symlink(String target, String linkPath) {
        if (target == null || target.isEmpty() || target.indexOf('\0') >= 0) {
            throw new FsException(Errno.EINVAL, "bad symlink target");
        }
        Loc loc = resolve(linkPath, false);
        if (loc.special() || loc.node() != null) {
            throw new FsException(Errno.EEXIST, linkPath);
        }
        if (loc.trailing()) {
            throw new FsException(Errno.ENOENT, linkPath);
        }
        PathRules.validateName(loc.name());
        need(loc.parent(), 2);
        List<Node> held = lockAll(loc.parent());
        try {
            if (!loc.parent().live) {
                throw new FsException(Errno.ENOENT, "parent was removed: " + linkPath);
            }
            if (loc.parent().getLocked(loc.name()) != null) {
                throw new FsException(Errno.EEXIST, linkPath);
            }
            tree.hook.beforeMutate();
            loc.parent().putLocked(loc.name(), new Node.Link(tree.ids.getAndIncrement(), user.name(), target));
        } finally {
            unlockAll(held);
        }
    }

    /** Changes the permission bits of the entry; only the owner or the superuser may. Takes no lock: the mode is a volatile int, so a concurrent reader sees the old or the new value. */
    @Override
    public void chmod(String path, int mode) {
        Loc loc = resolve(path, true);
        if (loc.node() == null) {
            throw new FsException(Errno.ENOENT, path);
        }
        if (mode < 0 || mode > 0777) {
            throw new FsException(Errno.EINVAL, "mode " + Integer.toOctalString(mode));
        }
        if (!user.superuser() && !loc.node().owner.equals(user.name())) {
            throw new FsException(Errno.EPERM, "only the owner may chmod " + path);
        }
        loc.node().mode = mode;
    }

    // ---------------------------------------------------------------- reads

    /** Lists child names in sorted order; needs read permission on the directory. */
    @Override
    public List<String> ls(String path) {
        Loc loc = resolve(path, true);
        if (loc.node() == null) {
            throw new FsException(Errno.ENOENT, path);
        }
        if (!(loc.node() instanceof Node.Dir dir)) {
            throw new FsException(Errno.ENOTDIR, path);
        }
        need(dir, 4);
        return new ArrayList<>(dir.copy().keySet());
    }

    /** Returns a copy of the file bytes; needs read permission on the file. */
    @Override
    public byte[] read(String path) {
        Loc loc = resolve(path, true);
        if (loc.node() == null) {
            throw new FsException(Errno.ENOENT, path);
        }
        if (!(loc.node() instanceof Node.Regular file)) {
            throw new FsException(Errno.EISDIR, path);
        }
        need(file, 4);
        return file.read();
    }

    /** Returns the stored target text of a symlink without following it. */
    @Override
    public String readlink(String path) {
        Loc loc = resolve(path, false);
        if (loc.special()) {
            throw new FsException(Errno.EINVAL, path);
        }
        if (loc.node() == null) {
            throw new FsException(Errno.ENOENT, path);
        }
        if (!(loc.node() instanceof Node.Link link)) {
            throw new FsException(Errno.EINVAL, path + " is not a symlink");
        }
        return link.target;
    }

    /** Describes the entry, following a trailing symlink. */
    @Override
    public Stat stat(String path) {
        return describe(path, true);
    }

    /** Describes the entry itself, without following a trailing symlink. */
    @Override
    public Stat lstat(String path) {
        return describe(path, false);
    }

    /**
     * Shared body of stat and lstat.
     *
     * @param path       the path
     * @param followLast whether to follow a trailing symlink
     * @return the description
     */
    private Stat describe(String path, boolean followLast) {
        // POSIX: a trailing slash makes even lstat resolve the link, because "l/" can only mean the directory it points to
        Loc loc = resolve(path, followLast || path.endsWith("/"));
        if (loc.node() == null) {
            throw new FsException(Errno.ENOENT, path);
        }
        return toStat(loc.node());
    }

    /**
     * Builds the read-only description of a node.
     *
     * @param n the node
     * @return its Stat
     */
    private static Stat toStat(Node n) {
        String target = n instanceof Node.Link l ? l.target : null;
        return new Stat(n.id, n.kind(), n.size(), n.owner, n.mode, target);
    }

    // ---------------------------------------------------------------- find

    /** Walks the tree from the start path and returns the matching paths in a stable order. */
    @Override
    public List<String> find(String start, FindQuery query) {
        Glob glob = query.nameGlob() == null ? null : Glob.compile(query.nameGlob());
        Loc loc = resolve(start, true);
        if (loc.node() == null) {
            throw new FsException(Errno.ENOENT, start);
        }
        Deque<String> parts = PathRules.components(start);
        String base = parts.isEmpty() ? "/" : "/" + String.join("/", parts);
        String name = parts.isEmpty() ? "" : parts.peekLast();
        List<String> out = new ArrayList<>();
        Node.Dir holder = loc.parent() == null ? tree.root : loc.parent();
        visit(base, name, holder, loc.node(), query, glob, new HashSet<>(), out);
        return out;
    }

    /**
     * Visits one entry: reports it when it matches, then walks into it when it is a directory. With symlink following on, a link is replaced by what it points to; a dangling link stays a link. A directory already on the current walk path is not entered again, which is how loops are avoided.
     *
     * @param path      the path to print for this entry
     * @param name      the entry's own name, matched by the glob
     * @param holder    the directory that contains this entry, used to resolve a relative link
     * @param node      the entry
     * @param q         the filters
     * @param glob      the compiled name glob, or null
     * @param ancestors ids of directories on the current walk path
     * @param out       where matches are added
     */
    private void visit(String path, String name, Node.Dir holder, Node node, FindQuery q, Glob glob, Set<Long> ancestors, List<String> out) {
        Node eff = node;
        if (node instanceof Node.Link link && q.followSymlinks()) {
            try {
                Loc target = walk(link.target.startsWith("/") ? tree.root : holder, link.target, true, new int[1]);
                if (target.node() != null) {
                    eff = target.node();
                }
            } catch (FsException e) {
                eff = node;
            }
        }
        if (matches(eff, name, q, glob)) {
            out.add(path);
        }
        if (!(eff instanceof Node.Dir dir) || ancestors.contains(dir.id) || !can(dir, 4) || !can(dir, 1)) {
            return;
        }
        ancestors.add(dir.id);
        for (Map.Entry<String, Node> e : dir.copy().entrySet()) {
            String child = path.equals("/") ? "/" + e.getKey() : path + "/" + e.getKey();
            visit(child, e.getKey(), dir, e.getValue(), q, glob, ancestors, out);
        }
        ancestors.remove(dir.id);
    }

    /**
     * Applies the name, kind and size filters to one entry.
     *
     * @param n    the entry (after any symlink following)
     * @param name the entry's own name
     * @param q    the filters
     * @param glob the compiled glob, or null
     * @return true when every filter accepts it
     */
    private static boolean matches(Node n, String name, FindQuery q, Glob glob) {
        if (glob != null && !glob.matches(name)) {
            return false;
        }
        if (q.kind() != null && n.kind() != q.kind()) {
            return false;
        }
        if (q.minSize() != null || q.maxSize() != null) {
            if (n.kind() != Kind.FILE) {
                return false;
            }
            long size = n.size();
            return (q.minSize() == null || size >= q.minSize()) && (q.maxSize() == null || size <= q.maxSize());
        }
        return true;
    }

    // ---------------------------------------------------------------- snapshot

    /** Dumps every entry as one sorted text line so tests can compare whole trees. */
    @Override
    public List<String> snapshot() {
        List<String> out = new ArrayList<>();
        dump("/", tree.root, out);
        Collections.sort(out);
        return out;
    }

    /**
     * Adds one line for a node and recurses into directories (symlinks are not followed).
     *
     * @param path where the node sits
     * @param n    the node
     * @param out  the lines so far
     */
    private void dump(String path, Node n, List<String> out) {
        Stat s = toStat(n);
        String live = n instanceof Node.Dir d ? String.valueOf(d.live) : "-";
        String content = n instanceof Node.Regular f ? java.util.HexFormat.of().formatHex(f.read()) : "-";
        out.add(path + "|" + s.kind() + "|" + s.id() + "|" + s.owner() + "|" + Integer.toOctalString(s.mode()) + "|" + s.size() + "|" + s.target() + "|" + live + "|" + content);
        if (n instanceof Node.Dir d) {
            for (Map.Entry<String, Node> e : d.copy().entrySet()) {
                dump(path.equals("/") ? "/" + e.getKey() : path + "/" + e.getKey(), e.getValue(), out);
            }
        }
    }
}
