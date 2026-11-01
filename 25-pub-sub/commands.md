# Topic 25: Pub-Sub, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
mvn -q -pl 25-pub-sub test                                         # all 33 tests, about 2 seconds (my laptop, JDK 21.0.8, 3 runs: 1.9 to 2.2 s)
mvn -q -pl 25-pub-sub test -Dtest=PubSubTest                       # offsets, redelivery, DLQ, backpressure
mvn -q -pl 25-pub-sub test -Dtest=ConcurrencyTest                  # parallel consumers and ordering
mvn -q -pl 25-pub-sub test -Dtest='PubSubTest#missedDeadlineRedeliversThenDeadLetters'
```

`-q` means quiet: no output means every test passed. Threaded tests have a 10 second default timeout from `src/test/resources/junit-platform.properties`.

## Mutation check

Breaking the code on purpose makes tests fail. For example, changing `>=` to `>` in `Subscription.fail` (one extra attempt) fails 4 tests, including `nackThreeTimesGoesToDlqAfterExactlyThreeDeliveries` (expected 3 deliveries, got 4). Removing the busy-key check fails `sameKeyIsSerialAndDifferentKeyIsNot` and `retryKeepsKeyOrder` by assertion, and `sameKeyWaitsWhileFirstIsRunning` fails its own assertion (the first version of `ConsumerPool.close()` could hang and hid that failure behind a 20 s timeout; `close()` now sets a flag and handles an interrupted handler). Commenting out the three `clear()` calls in `seekLocked` fails `seekForgetsInFlightAndRetryWaitingWork` and `seekBackMakesOldReceiptStaleAndKeyFree`. Dropping the retry and in-flight terms from `lagLocked` fails `lagCountsUnreadRetryAndInFlight`. Reverting the `close()` fix makes `closeReturnsAndDoesNotBurnAnAttempt` time out.
