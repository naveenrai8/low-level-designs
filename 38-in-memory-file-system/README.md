# Topic 38: In-Memory File System

"Design a file system" looks like a tree of folders and files, and v1 really is that small. The interviewer is waiting for what a real file system does that a toy forgets: `..` at the top, a symlink that points to itself, a folder you may not walk through, and two threads moving things at the same moment.

A staff answer is a Composite tree where every directory has its own lock, paths are resolved one name at a time with a bounded number of symlink hops, every failure is a typed POSIX-style error, every call checks everything before it changes anything, and a move across two directories takes both locks in a fixed order.

## 1. The Problem

### Words first

- A *path* like `/a/b/c.txt` is a list of names (*components*). `/` is the *root*. A *symlink* is an entry that stores another path as text; a lookup that reaches it continues there.
- `.` means "this directory" and `..` means "the parent". An *id* is a unique number given to every entry when created, never reused.
- Permissions are `r` read, `w` write, `x` execute. On a directory, `x` means *search*: the right to walk through it.
- An *errno* is the short name of a failure, like `ENOENT` ("no such entry"). We use the Linux names.

### Worked example first

The tree is `/a/b` (a directory) and `/a/link` (a symlink to `b`). Alice runs `cat /a/link/../b/../../x`.

| Step | Name read | What happens | Now at |
|---|---|---|---|
| 1 | `a` | Directory `a` found | `/a` |
| 2 | `link` | A symlink to `b`, so we continue at `b` (relative to `/a`) | `/a/b` |
| 3 | `..` | Parent of where we are (the physical parent) | `/a` |
| 4 | `b` | Directory `b` | `/a/b` |
| 5, 6 | `..`, `..` | Parent, then parent again | `/` |
| 7 | `x` | Looked up in `/` | the file `/x` |

Each step needs `x` on the directory we stand in. If `link` pointed back at itself, the walk would never end, so after 40 symlink hops it stops with `ELOOP`.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Operations? | `mkdir`, `ls`, `cat`/`read`, `write`, plus `rm`, `rmdir`, `rename`. Paths are absolute only (relative is `EINVAL`, empty is `ENOENT`). |
| Content and size? | `byte[]`, size is `long` bytes. `write` takes `OVERWRITE` (replace) or `APPEND` (add to the end, atomic). |
| Users and errors? | Owner and "other" bits (group bits are ignored); `root` skips checks. One `FsException` with an `Errno`; a failed call changes nothing. |
| Out of scope | Hard links, groups, ACLs, sticky bit, setuid, file descriptors, mounts, persistence, timestamps (so no `Clock` or `Ticker`). |

---

## 2. How It Solves It

### Entities and invariants

| Entity | Meaning |
|---|---|
| `Node` (sealed) | `Dir`, `Regular`, `Link`. Every node has an id, an owner, mode bits and its own lock. This is the Composite pattern: a `Dir` holds `Node`s. |
| `FileSystem` / `InMemoryFileSystem` | The interface with its error contract, and the implementation. `as(user)` gives a view of the same tree for another user. |
| `Errno` / `FsException` | The error kinds (below) and the one exception that carries them. |
| `Glob`, `FindQuery`, `Hook` | The name matcher, the find filters, and a test seam that pauses a thread at an exact step (does nothing in production). |

- I1: the tree stays a tree. Every directory has one parent, and no directory is its own ancestor.
- I2: a directory's child map is only touched while holding that directory's lock.
- I3: a failed call changes nothing. Every check runs before the first change.
- I4: ids are never reused, so "lock in ascending id" is a stable global order.
- I5: a removed directory is marked dead, and nothing can be added to a dead directory.

### The interface and its error contract

```java
public interface FileSystem {
    void mkdir(String path);                          // ENOENT, EEXIST (also "l/" on a link), ENOTDIR, EACCES, EINVAL
    List<String> ls(String path);                     // ENOENT, ENOTDIR, EACCES, ELOOP
    byte[] read(String path);                         // ENOENT, EISDIR, ENOTDIR, EACCES   (cat() is the text form)
    void write(String path, byte[] d, WriteMode m);   // ENOENT, ENOTDIR, EISDIR, EACCES, EINVAL, EFBIG
    void rm(String path);                             // files and links: ENOENT, EISDIR, ENOTDIR (trailing /), EACCES, EBUSY
    void rmdir(String path);                          // ENOTDIR (a link, too), ENOTEMPTY, EACCES, EBUSY
    void rename(String from, String to);              // EINVAL (into itself), EISDIR, ENOTDIR, ENOTEMPTY, EACCES, EBUSY
    void symlink(String target, String linkPath);     // EEXIST, ENOENT (trailing /), EINVAL (empty or NUL target)
    String readlink(String path);                     // ENOENT, EINVAL (not a link)
    void chmod(String path, int mode);                // ENOENT, EPERM, EINVAL
    Stat stat(String path);  Stat lstat(String path); // ENOENT, ENOTDIR, EACCES, ELOOP
    List<String> find(String start, FindQuery q);     // EINVAL (bad glob), ENOENT, EACCES
}
```

| Errno | Linux number | When |
|---|---|---|
| `EPERM` | 1 | `chmod` by someone who is not the owner |
| `ENOENT` | 2 | a component is missing, or the path is empty |
| `EACCES` | 13 | a needed `r`, `w` or `x` bit is missing |
| `EBUSY` | 16 | remove or rename the root |
| `EEXIST` | 17 | the name is taken (a dangling symlink counts as taken) |
| `ENOTDIR` / `EISDIR` | 20 / 21 | a file used as a directory / a directory used as a file |
| `EINVAL` | 22 | bad name or NUL, bad mode, bad glob, relative path, move into itself |
| `EFBIG` | 27 | a file would grow past about 2 GB (one Java array) |
| `ENOTEMPTY` | 39 | `rmdir`, or `rename` onto a directory that has entries |
| `ELOOP` | 40 | more than 40 symlink hops |

### Path resolution, in plain steps

| Time | What happens | Result |
|---|---|---|
| 1 | Split the path on `/`, drop empty pieces | `//a///b/` becomes `a`, `b` (remember the trailing slash) |
| 2 | For each name, check `x` on the directory we stand in | no `x`: `EACCES` |
| 3 | `.` stays. `..` moves to the parent, and the root's parent is the root | `/../../x` is `/x` |
| 4 | A symlink in the middle, or in the last place when the call follows links, is expanded: its target's names are pushed in front of the rest | the hop counter goes up |
| 5 | Counter above 40 | `ELOOP` |
| 6 | The last name may be missing (create calls need that), anything earlier missing is `ENOENT` | |

The cost is one map lookup per name plus the hops, so `O(path length + hops)`. The rules, each with a test:

- `..` at the root stays at the root. POSIX says "in the root directory, dot-dot may refer to the root directory itself" (POSIX.1-2017, 4.13). Test `dotDotPastRootStaysAtRoot`.
- `..` is *physical*, not lexical. After `/other/link -> /x/y`, the path `/other/link/..` is `/x`. A shell's `cd ..` is lexical, a kernel's `..` is not. Test `dotDotAfterASymlinkIsPhysical`.
- At most 40 symlinks per path, shared across the whole walk: `path_resolution(7)` says "the maximum number of symbolic links that will be followed while resolving a pathname is 40". Tests `hopBoundIsForty`, `hopBudgetIsSharedAcrossThePath`.
- `mkdir`, `rm`, `rmdir`, `rename`, `symlink`, `readlink` and `lstat` do NOT follow a last-position symlink (they act on the link; `unlink(2)` and `rename(2)` do the same). `read`, `write`, `ls`, `stat`, `chmod` and `find` do.
- A trailing slash demands a directory, and it does NOT make the unfollowed calls follow the link (Linux behaviour). So `rmdir("/dl/")` and `rename("/dl/", x)` give `ENOTDIR` and leave the target alone, and `mkdir("/dangling/")` is `EEXIST`. The one exception is `lstat("/l/")`, which follows, as POSIX 4.13 says. A link target that ends in `/` must lead to a directory. Tests in `SymlinkTest`.
- Names must be non-empty, not `.` or `..`, with no `/` or NUL.

### Permissions

A check is "owner bits if you own the node, else the other bits". `root` passes everything. Mutating calls check before taking their locks (`rename` checks again under its locks), so a concurrent `chmod` can race with a call, the same as on Linux.

| Call | Needs |
|---|---|
| any path walk | `x` on every directory passed through |
| `ls` | `r` on the directory |
| `read` / `write` an existing file | `r` / `w` on the file |
| create, `rm`, `rmdir`, `rename`, `symlink` | `w` AND `x` on the parent (both parents for `rename`) |
| `rename` of a directory to a NEW parent | also `w` on the directory itself (`rename(2)` EACCES: the `..` entry is rewritten) |
| `chmod` | be the owner (else `EPERM`) |

Test `deniedTraversalNeedsSearchOnEveryDirectory`: bob can read `/mine/secret` (mode 0644) until alice removes `x` from `/mine`, and then bob gets `EACCES` even though the file itself is world-readable.

### Find: glob and size

A small example first. `find("/", named("*.java").atLeast(5))` on `/src/a.java` (5 bytes), `/src/b.txt` (0 bytes), `/src/sub/c.java` (10 bytes):

| Step | Entry | Name matches `*.java`? | Size >= 5? | Result |
|---|---|---|---|---|
| 1 | `/` | no | (a directory is not a file) | skip, walk into it |
| 2 | `/src/a.java` | yes | 5 >= 5 | listed |
| 3 | `/src/b.txt` | no | | skip |
| 4 | `/src/sub/c.java` | yes | 10 >= 5 | listed |

Glob matching works the same way on one name: keep the set of positions reachable so far. For `a*b` against `axxb`: after `a` only position 1, after `*` positions 1 to 4, after `b` only 4, which is the end, so it matches. No backtracking, so `a*a*a*b` on 5000 letters is instant.

- Glob: `*` (any run), `?` (one code point), `[abc]`, `[a-c]`, `[!abc]`, `\x` to quote. A leading `]` and a leading or trailing `-` in a set are plain members (glob(7)). A backslash inside a set quotes the next character (our rule; glob(7) leaves it open). It is matched against the entry's own name, like `find -name`.
- `*` and `?` match a leading dot, as `find -name` does since findutils 4.2.2 (IEEE PASC interpretation 126, quoted in find(1)).
- `**` is OUT: a name never has a `/`, so `**` would equal `*` and is treated so. `{a,b}` is out too.
- Size: `atLeast`/`atMost` in `long` bytes, both inclusive. A size filter keeps regular files only.
- Symlinks: the start path is followed (like `find -H`), links below it are not (like `find -P`) unless `following(true)`. With following on, a directory already on the current walk path is skipped silently (`find -L` prints a diagnostic), and a directory reachable by two links is listed twice (as `find -L` does).
- A directory the caller cannot read and search (`r` and `x` together) is skipped silently; real `find` prints an error and goes on.

### Concurrency model

- Shared state: each directory's child map (I1, I2). Primitive: one `ReentrantLock` per node. Path lookup takes one directory lock at a time and lets go. A change then locks what it changes and **re-checks** that the name is still what it saw, because another thread may have changed it in between. If not, it retries or fails with the right error.
- Two directories (`rename`): lock both parents (and a replaced directory) in ascending id. This is the rule from [Topic 08](../08-multi-object-atomicity/); the thread basics are in [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/).
- Why a plain order is not enough for loops. Worked example: `/p/a` and `/q/b` exist. Thread 1 runs `mv /p/a /q/b/a`, thread 2 runs `mv /q/b /p/a/b`.

| Time | Thread 1 | Thread 2 | Result without the extra lock |
|---|---|---|---|
| 1 | resolves; `/q/b` is not inside `/p/a`, looks fine | resolves; `/p/a` is not inside `/q/b`, looks fine | both checks pass |
| 2 | locks `/p` and `/q/b` | locks `/q` and `/p/a`: a different set, no waiting | both proceed |
| 3 | `a` is now under `b` | `b` is now under `a` | a loop cut off from the root |

So a move of a *directory* to another parent also takes one tree-wide `renameLock` first, and checks the "inside itself" rule under it (`EINVAL`). File moves and everything else stay fully parallel.
- A removed or replaced directory is marked dead under its lock, so an earlier-resolved `mkdir` fails with `ENOENT`. Append is atomic under the file's lock.
- What this does NOT give: the tree is atomic per directory, **not linearizable per path**. T1 resolves `/a/b` for `mkdir("/a/b/x")`, T2 renames `/a/b` to `/c`, T3's `stat("/c/x")` says `ENOENT`, then T1 creates `/c/x`. No single order of the three calls explains that history. Linux behaves the same. A rename generation counter with retry would close it; it is not built.
- Opposite renames `/a/x -> /b/x` and `/b/y -> /a/y` both sort their locks to (`/a`, `/b`), so one waits for the other and nobody deadlocks.

### Code and tests (70 tests)

| File | What it shows |
|---|---|
| `FileSystem.java`, `Errno.java`, `FsException.java` | The interface and error contract, written first |
| `InMemoryFileSystem.java`, `Node.java`, `Glob.java`, `PathRules.java` | Walk, checks, locking, find, snapshot; the Composite; the matcher; name rules |
| `PathTest` (15), `SymlinkTest` (15), `PermissionTest` (9) | Dots and slashes, every error kind, loops, the 40 bound, link rules, rwx and denied traversal |
| `GlobTest` (6), `FindTest` (10) | 5000 random globs vs `FileSystem#getPathMatcher("glob:...")`; 300 random trees vs a brute-force walk |
| `ConcurrencyTest` (7), `RaceTest` (8) | Forced interleavings and "the tree changed under me" cases, with strict barriers |

Every failure test uses `Check.failsCleanly`, which compares a full snapshot (path, kind, id, owner, mode, size, link target, live flag, file content as hex) before and after, so "validate before mutate" is checked by equality.

---

## 3. The Gaps

### F1 and F2 (built)

F1 is the path, symlink and permission rules above. F2 is `find` above. The differential tests: names against the JDK matcher over the subset where glob(7) and the JDK agree (the generator leaves out a leading `]` and a backslash inside a set, which are pinned by `setEdgeCases` instead), and `find` against a loop over `snapshot()`, which does not use the walker. The brute-force oracle covers name and size filters on the default (no-follow) mode; following, kinds and permissions have their own example tests.

### F3: Concurrency (built)

Per-directory locks and the fixed order, as above. The tests are deterministic:

- `concurrentMkdirSameNameExactlyOneWins`: a strict barrier in `Hook.beforeLock` makes both threads finish their unlocked check first. Without the re-check both win.
- `oppositeRenamesDoNotDeadlock`: both renames start together. Without the sort the run deadlocks and the 10 second `SEPARATE_THREAD` timeout fails it. `ThreadMXBean.findDeadlockedThreads` is used only in the failure message to say who is stuck.
- `crossingDirectoryMovesCannotMakeALoop`: both renames pass `afterResolve` together, so without `renameLock` both succeed.
- `RaceTest`: park one call after it resolved its path, change the tree, release it, and check it noticed (`rm` over a new directory, `rmdir` over a new non-empty one, `rename` into a removed or replaced directory, and so on).
- A barrier that MUST fill uses a strict 5 second await that fails the test. Only barriers the correct code may never fill (the partner is blocked on a lock) give up after 300 ms; that is a bounded wait, not a sleep.

### Misconceptions and common mistakes

- "`..` just drops the last name": that is the lexical rule, wrong after a symlink. And "sort locks by name": a path can change, so sort by id.
- "A trailing slash means follow the link": for `rmdir`, `rename` and `mkdir` it does not. `rmdir("/link/")` must not delete the target directory.
- "`*` skips dotfiles": that is the shell. `find -name '*'` matches `.hidden`.
- "The re-check under the lock makes it linearizable": it only covers the one directory entry, not the path above it.

### Trade-offs and production delta

- A global lock is correct but serial; per-directory locks let `/a` and `/b` work in parallel (test `differentDirectoriesDoNotBlockEachOther`). A single `ReadWriteLock` is the simpler choice if the tree is small; I did not measure the crossover.
- `find` is weakly consistent: a concurrent move may be seen twice or missed. A frozen view needs copy-on-write.
- A file is one `byte[]` (about 2 GB, then `EFBIG`); real systems use pages, offsets, file descriptors, a journal and `fsync`.
- HDFS keeps the whole namespace on one NameNode that "executes file system namespace operations like opening, closing, and renaming files and directories", and it has no links, so a rename is a metadata change in one place. S3 "maintain[s] a flat key namespace" where a folder is only a key prefix, so a "rename" is copy plus delete (see References).

---

## 4. Official References

| Source | Used for |
|---|---|
| [POSIX.1-2017, Pathname Resolution (4.13)](https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap04.html) | `.`, `..`, `..` at the root, trailing slash |
| [`path_resolution(7)`](https://man7.org/linux/man-pages/man7/path_resolution.7.html) | The limit of 40 symlinks, `ELOOP`, `x` on each directory |
| [`rename(2)`](https://man7.org/linux/man-pages/man2/rename.2.html), [`unlink(2)`](https://man7.org/linux/man-pages/man2/unlink.2.html) | `EINVAL` for a move into itself, `EISDIR`, `ENOTDIR`, `ENOTEMPTY`, `w` on a moved directory; links are not followed |
| [`errno(3)`](https://man7.org/linux/man-pages/man3/errno.3.html), [`errno-base.h`](https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/asm-generic/errno-base.h), [`errno.h`](https://raw.githubusercontent.com/torvalds/linux/master/include/uapi/asm-generic/errno.h) | Error names and numbers |
| [`glob(7)`](https://man7.org/linux/man-pages/man7/glob.7.html), [`FileSystem#getPathMatcher`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/FileSystem.html) | Glob rules and the JDK matcher used as a reference |
| [`find(1)`](https://man7.org/linux/man-pages/man1/find.1.html) | `-name`, leading dot (PASC 126), `-H`/`-L`/`-P` |
| [HDFS Architecture](https://hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-hdfs/HdfsDesign.html), [S3 prefixes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-prefixes.html) | The production-delta remarks |
| Topic 08, [Multi-Object Atomicity](../08-multi-object-atomicity/) | Lock ordering |

---

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-10 | Ask about absolute paths, users, symlinks, threads. Draw the Composite and write the interface and error enum. |
| 10-45 | Path resolution and `mkdir`/`ls`/`write`/`cat`. State the `..`, hop and `x` rules, then `rm`/`rename` on links, find, and the lock plan. Name the directory-loop race, the dead-parent case, and the tests (snapshot equality, barriers). |

---

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Paths and errors | Split on `/`, return null | Root parent, physical `..`, hop bound, typed errno, failure leaves the tree unchanged |
| Concurrency | One global lock | Per-directory locks by id, re-check, loop-proof moves, says what is NOT linearizable |

---

## 7. Key Takeaways

- Resolve a path one name at a time; that is where `..`, symlinks and permissions all live.
- Check everything, then change: a failed call leaves the tree identical, content included.
- Lock per directory in ascending id, re-check under the lock, guard directory moves with one extra lock, and be honest that paths are not linearizable.

[← Topic 37: Spreadsheet](../37-spreadsheet/) · [Index](../README.md) · [Topic 39: Undo/Redo Editor](../39-undo-redo-editor/)

**Self-grade: STRONG.** Remaining gap: groups and ACLs, file descriptors and hard links are described but not built; `find` is weakly consistent; per-path linearizability (a rename generation counter) is described but not built; and the lock-free `volatile` reads in `Node` are not covered by a jcstress test.
