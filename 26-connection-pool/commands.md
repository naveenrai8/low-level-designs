# Topic 26: Connection Pool, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests

```bash
mvn -o -q -pl 26-connection-pool test                                                     # all 32 tests, about 2 seconds wall time (3 runs of 1.8 to 2.4 s on an Apple M3 Max, JDK 21.0.8, offline Maven)
mvn -o -q -pl 26-connection-pool test -Dtest='ConnectionPoolTest#checkThenCreateRaceCreatesExactlyOne'   # one test
python3 scripts/check_style.py 26-connection-pool                                         # prints "0 problem(s)"
```

`-q` means quiet: no output means every test passed. Every threaded test has a JUnit `@Timeout`, and `src/test/resources/junit-platform.properties` sets a 10 second default, so a broken pool fails instead of hanging.

## Try a mutation yourself

Edit `BoundedPool.java`, run the tests, then undo the edit. Each change below is caught.

| Change | What fails |
|---|---|
| `total < config.max()` to `total <= config.max()` in `borrow` | Real output: `Tests run: 32, Failures: 14` (the peak, the check-then-create race and more; it takes about 50 s because the broken ones hit their timeouts) |
| Swap `closeQuietly` and `giveBackSlot` in `validateOrDrop`, `invalidate` or `evictIdle` | `validateDropFreesSlotOnlyAfterClose` (`expected: <1> but was: <2>`), `invalidateFreesSlotOnlyAfterClose`, `evictionFreesSlotOnlyAfterClose` |
| `signal()` to `signalAll()` in `stash` | `returnWakesExactlyOneWaiter:237 expected: <false> but was: <true>` |
| Drop the `closed` check in `lend` | `closeWhileOpeningRefusesAndLeaksNothing` |
| Unlock around `probe.beforeWait()` (check and wait in two lock holds) | `returnBetweenCheckAndWaitIsNotLost` times out |
| `min()` to `max()` in `warmUp`; `<` to `<=` in the eviction age test; remove the deadline check after a bad idle connection; remove the zero-leak-threshold check | `warmUpOpensExactlyMin`, `evictionBoundaryIsInclusive`, `badIdleConnectionsCannotPushBorrowPastDeadline`, `zeroLeakThresholdMeansOff` |
| Delete `available.signal()` in `giveBackSlot` | `failedCreateWakesAWaiter` (times out, no hang) |

The pass-on `available.signal()` in the interrupt `catch` of `borrow` is defensive. With a `ReentrantLock` it cannot be forced from a test, so no test covers it.
