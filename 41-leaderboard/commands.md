# Topic 41: Leaderboard, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -q -pl 41-leaderboard test                                  # all 30 tests, about 2.5 seconds wall time
mvn -q -pl 41-leaderboard test -Dtest=ComplexityTest            # prints the comparison counts
mvn -q -pl 41-leaderboard test -Dtest=DifferentialTest          # 20 seeds against the naive model
mvn -q -pl 41-leaderboard test -Dtest=ConcurrencyTest           # 8 threads behind a start gate
```

`-q` means quiet: no output means every test passed. `ComplexityTest` prints (measured, JDK 21.0.8):

```
avg comparisons per rank(): n=1k 12.9, n=10k 17.3, n=100k 21.7
avg comparisons per update at n=100k: 41.9
height at n=50k: 41 (log2 n = 15.6)
naive scan: 100000 comparisons per rank vs tree 21.7 (about 4609x fewer)
```

## 2. Style and warning gates

```bash
mvn -o -pl 41-leaderboard test-compile | rg WARNING     # prints nothing
python3 scripts/check_style.py 41-leaderboard           # 0 problem(s)
```

## 3. Mutation self-check

Each row: one line changed in the main code, the whole suite run, then the line restored. "Killed" means at least one test failed. Run on JDK 21.0.8.

| Mutation | Killed by |
|---|---|
| Skip `tree.remove(old)` in `setScore` (duplicates) | `DifferentialTest`, `updateMovesEntryWithoutDuplicate`, `windowedBoardLosesNoUpdates` |
| Tie-break by later time | `tieBreaksByTimeThenUserId`, `DifferentialTest` |
| Competition-rank probe uses `Long.MAX_VALUE` | `competitionRankSharesAndSkips`, `DifferentialTest` |
| Same-score write no longer a no-op | `sameScoreKeepsOriginalReachTime`, `DifferentialTest` |
| `top(k)` returns k-1 rows | `setIncrementAndTop`, `readsTouchOnlyLogNodes`, `DifferentialTest` |
| `countLess` forgets the `+ 1` | `DifferentialTest`, `removeClosesTheGap`, `rankOnLiveWindow` |
| `rank` also does an O(n) `slice(0, MAX).indexOf` (comparator never called) | `readsTouchOnlyLogNodes` (node-visit counter) |
| `top`/`range` slice via an O(n) copy with the same output | `readsTouchOnlyLogNodes` |
| Window start computed in UTC | `boundaryInstantBelongsToNextWindow`, `dstDayIsTwentyThreeHours`, `dstFallBackDayIsTwentyFiveHours` |
| Week starts on Sunday | `weeklyStartsOnMonday` |
| Expiry keeps one window too many | `oldWindowsExpire`, `expiryIsByCalendarWithSparseActivity` |
| Stale-window check removed | `staleWriteIsRefused`, `forwardJumpKeepsTheNewWindowAndRefusesOldOnes` |
| Windowed `rank` always empty | `rankOnLiveWindow` |
| Windowed `increment` loses `synchronized` | `windowedBoardLosesNoUpdates` |
| Codec time unflipped | `ZsetCodecTest` (4 tests) |
| Codec drops the `seconds < 0` check | `rejectsOutOfRange` |
| Wrapper `setScore` takes the read lock | `writesForwardExactlyAndHoldTheWriteLock`, `readerBlocksWhileAWriterIsMidUpdate` |
| Wrapper `setScore` passes `score + 1` | `writesForwardExactlyAndHoldTheWriteLock` |
| Wrapper `remove` returns false | `writesForwardExactlyAndHoldTheWriteLock` |
| Wrapper `competitionRank` calls `rank` | `readsForwardExactlyAndHoldTheReadLock` |
| Wrapper `range` calls `top(toRank)` | `readsForwardExactlyAndHoldTheReadLock` |
| Wrapper `rank` drops the read lock | `readsForwardExactlyAndHoldTheReadLock`, `readerBlocksWhileAWriterIsMidUpdate` |
| Wrapper `increment` without any lock | `writesForwardExactlyAndHoldTheWriteLock`, `ConcurrencyTest` |

Notes:
- The no-lock mutants fail `ConcurrencyTest` with a `StackOverflowError` from a corrupted tree, not with a wrong total. Unsynchronised access to a treap breaks the structure itself.
- Comparator counting cannot see a scan that never calls the comparator. That is why `OrderStatisticTree` has a node-visit counter.
