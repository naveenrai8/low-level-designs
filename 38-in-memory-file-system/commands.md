# Topic 38: In-Memory File System, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 38-in-memory-file-system test                                  # 70 tests, about 10 seconds; no output means all passed
mvn -o -q -pl 38-in-memory-file-system test -Dtest=PathTest                  # dots, slashes, names, error kinds, rename rules
mvn -o -q -pl 38-in-memory-file-system test -Dtest=SymlinkTest               # loops, the 40 hop bound, physical dot-dot, rm and rename of a link
mvn -o -q -pl 38-in-memory-file-system test -Dtest=PermissionTest            # rwx, denied traversal
mvn -o -q -pl 38-in-memory-file-system test -Dtest='GlobTest,FindTest'       # glob vs the JDK matcher, find vs a brute-force walk
mvn -o -q -pl 38-in-memory-file-system test -Dtest='ConcurrencyTest,RaceTest'  # forced interleavings, parked calls, no sleeps
```

`src/test/resources/junit-platform.properties` gives every test a 10 second timeout and runs it in a separate thread (`SEPARATE_THREAD`), so a mutation that deadlocks fails the test instead of hanging the run.

## 2. Gates

```bash
mvn -o -pl 38-in-memory-file-system test-compile | rg WARNING                # nothing
python3 scripts/check_style.py 38-in-memory-file-system                      # 0 problem(s)
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now' 38-in-memory-file-system/src   # nothing
```

## 3. Mutation checks

Each change was a one-line edit to `InMemoryFileSystem` (or `Glob`), the full suite was run, and the file was restored. All rows below were re-run on the final code. Survivors are marked.

### 3a. Reviewer round (19 mutants that survived the first pass, now all killed)

| # | Change | Killing test |
|---|---|---|
| M1 | rename onto itself is not a no-op | `PathTest.renameOntoItselfIsANoOp` |
| M2 | `lstat` with a trailing slash does not follow | `SymlinkTest.trailingSlashOnLstatFollowsTheLink` |
| M2b | old behaviour: a trailing slash follows an unfollowed link | `SymlinkTest.rmdirWithTrailingSlashKeepsTheTarget`, `renameWithTrailingSlashKeepsTheTarget`, `mkdirWithTrailingSlashOnADanglingLink` |
| M3 | no name validation in `mkdir`/`write`/`symlink` | `PathTest.nulInANameIsRefusedEverywhere`, `SymlinkTest.symlinkNameAndTargetRules` |
| M3b | no name validation for the rename destination | `PathTest.nulInANameIsRefusedEverywhere` |
| M4 | no dead-parent check in `write`/`symlink`/`mkdir` | `RaceTest.writeIntoARemovedDirectory`, `symlinkIntoARemovedDirectory`, `mkdirIntoADirectoryReplacedByRename` |
| M5 | `rm` does not re-check the name | `RaceTest.rmSeesTheNameChange` |
| M6 | `rmdir` does not re-check the name | `RaceTest.rmdirSeesTheNameChange` |
| M7 | `rename` does not re-check the destination | `RaceTest.renameSeesTheDestinationChange` |
| M7b | `rename` does not re-check the source | `RaceTest.renameSeesTheSourceChange` (forced), `ConcurrencyTest.randomRenamesKeepEveryFileOnce` |
| M8 | `rename` ignores a dead destination parent | `RaceTest.renameIntoARemovedDirectory` |
| M9 | `rename` skips `w`/`x` on the destination parent | `PermissionTest.renameNeedsWriteOnTheDestinationParent` |
| M10 | a directory replaced by rename is not marked dead | `RaceTest.mkdirIntoADirectoryReplacedByRename` |
| M11 | `find` does not need `r` on a directory | `PermissionTest.findNeedsReadAndSearchTogether` (modes 0704 and 0701) |
| M11b | `find` does not need `x` on a directory | `PermissionTest.findNeedsReadAndSearchTogether` |
| M12 | file rename to a name with a trailing slash accepted | `PathTest.renameOfAFileWithATrailingSlash`, `SymlinkTest.renameWithTrailingSlashKeepsTheTarget` |
| M13 | `symlink` ignores a trailing slash on the link path | `SymlinkTest.symlinkNameAndTargetRules` |
| M14 | backslash inside a glob set is not an escape | `GlobTest.setEdgeCases` |

### 3b. First-pass mutants, re-run on the final code

| Change | Killing test |
|---|---|
| `..` at the root goes to `null` | `PathTest.dotDotPastRootStaysAtRoot` |
| symlink bound 41 | `SymlinkTest.hopBoundIsForty` |
| no `x` check in the walk | `PermissionTest.deniedTraversalNeedsSearchOnEveryDirectory` and 2 more |
| no `w` check on a file | `PermissionTest.fileBitsDecideReadAndWrite` |
| no `renameLock` | `ConcurrencyTest.crossingDirectoryMovesCannotMakeALoop`, `PathTest.cannotMoveADirectoryIntoItself` |
| locks in call order | `ConcurrencyTest.oppositeRenamesDoNotDeadlock` (times out), `randomRenamesKeepEveryFileOnce` |
| locks in DESCENDING id order | SURVIVES, and that is correct: any fixed global order is deadlock-free |
| `rmdir` does not mark the directory dead | `RaceTest.writeIntoARemovedDirectory` and 2 more |
| append ignores the mode | `ConcurrencyTest.appendsAreAtomic`, `PathTest.overwriteVersusAppendAndSize` |
| `find` size exclusive | `FindTest.sizeFilters`, `matchesABruteForceWalk` |
| `find` has no loop guard | `FindTest.followingSymlinksStopsAtLoops` |
| non-empty destination directory accepted | `PathTest.cannotMoveADirectoryIntoItself` |

The two first-pass survivors (no `renameLock`, no lock sort) were fixed by pausing at `Hook.afterResolve` and by an `armed` flag so setup calls do not use up the barriers. `mkdir` without the re-check is killed by `ConcurrencyTest.concurrentMkdirSameNameExactlyOneWins`.

## 3c. Not covered by jcstress

`Node.Dir.get` and `copy` read under the directory lock, but `mode` and `live` are `volatile` reads without a lock. No jcstress test covers them. For the technique see `../../internal-pocs/Java-poc/concurrency/11-jcstress`.

## 4. Measured

Setup: Apple M3 Max, OpenJDK 21.0.8 (aarch64). The whole suite (70 tests) takes about 3 seconds wall time including Maven start-up; the slowest test group is `ConcurrencyTest` at about 1 second, because the barrier grace period is 300 ms on the two forced-race tests.
