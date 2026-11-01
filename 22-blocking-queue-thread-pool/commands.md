# Topic 22: Bounded Blocking Queue and Thread Pool, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests

```bash
mvn -q -pl 22-blocking-queue-thread-pool test                                   # all 38 tests, about 2 s of test time
mvn -q -pl 22-blocking-queue-thread-pool test -Dtest=WaitLoopTest               # if-vs-while under a stolen wakeup
mvn -q -pl 22-blocking-queue-thread-pool test -Dtest=LostSignalTest             # one shared room vs two rooms
mvn -q -pl 22-blocking-queue-thread-pool test -Dtest=ShutdownTest               # shutdown, shutdownNow, awaitTermination
mvn -q -pl 22-blocking-queue-thread-pool test -Dtest=RejectionPolicyTest        # the four policies with exact counts
mvn -q -pl 22-blocking-queue-thread-pool test -Dtest=PoolConcurrencyTest        # 8 producers x 2,000 items, exact totals
```

`-q` means quiet: no output means every test passed. Per-class results are in `22-blocking-queue-thread-pool/target/surefire-reports/`.

## Quality gates

```bash
mvn -o -pl 22-blocking-queue-thread-pool test-compile | rg WARNING   # prints nothing: zero -Xlint warnings
python3 scripts/check_style.py 22-blocking-queue-thread-pool         # 0 problem(s)
```

## Try the bug yourself (mutation runs)

Every blocking call in the tests has a 5 second bound and a message naming the bug, and `src/test/resources/junit-platform.properties` caps any test at 10 seconds, so a broken queue fails fast instead of hanging. Edit `BoundedBlockingQueue.java`, run the whole suite, then put the file back. Measured on JDK 21.0.8, Apple-silicon laptop, one run each (the time includes Maven start-up):

| Mutation of the real queue | Tests that fail | Time |
|---|---|---|
| `take`: `while` to `if` | `WaitLoopTest.whileWaitSurvivesTheStolenWakeup` | 8 s |
| `put`: `while` to `if` | `WaitLoopTest.putWaitsInAWhileLoopToo`, `PoolConcurrencyTest.queueDeliversEveryItemExactlyOnceUnderContention` | 8 s |
| `enqueue` signals `notFull` instead of `notEmpty` | 7 tests, including `LostSignalTest.twoRoomsDeliverTheSignalToTheRightKindOfThread` | 43 s |
| `dequeue` loses its `notFull` signal | 3 tests, including `WaitLoopTest.putWaitsInAWhileLoopToo` | 22 s |
| `close()`: `signalAll` to `signal` on `notEmpty` | `LostSignalTest.closeWakesEveryTakerNotJustOne` and 2 pool tests | 28 s |
| `awaitNanos(remaining)` to `await()` in timed `poll` | `BoundedQueueTest.timedWaitSleepsOncePerRemainingSliceOfTime` (hits the 10 s limit) | 13 s |
| `remaining <= 0` to `< 0` in timed `poll` | `BoundedQueueTest.timedWaitSleepsOncePerRemainingSliceOfTime` | 2 s |
| remove `items[head] = null` | `BoundedQueueTest.takenSlotsAreClearedSoNothingLeaks` | 2 s |
| pool: `catch (Throwable)` to `catch (RuntimeException)` | `ShutdownTest.anErrorInATaskDoesNotKillTheOnlyWorker` | 8 s |
| pool: `closeAndDrain()` to `close()` then `drainAll()` | `ShutdownTest.shutdownNowClosesAndDrainsInOneStep` | 3 s |

What we learned: before the bounds existed, the `take` mutation did not fail, it hung for over 2 minutes. The stolen wakeup surfaced as `QueueClosedException` on a worker thread, and the test then waited forever on a future that never completed.
