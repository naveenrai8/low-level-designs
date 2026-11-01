# Topic 17: LRU / LFU Cache — Commands

Run every command from `low-level-designs/` with Java 21 and Maven 3.9+. This is a core module only: no Spring and no REST adapter, because it is an in-process design (see CLAUDE.md).

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+

mvn -q -pl 17-lru-lfu-cache test                                              # all 71 tests
mvn -q -pl 17-lru-lfu-cache test -Dtest=DifferentialTest                      # 1M seeded ops vs the reference models
mvn -q -pl 17-lru-lfu-cache test -Dtest=MinFreqCounterLfuTest                 # when the LeetCode minFreq trick breaks
mvn -q -pl 17-lru-lfu-cache test -Dtest=ConcurrencyTest                       # 8 threads x 50k ops, conservation laws
mvn -q -pl 17-lru-lfu-cache test -Dtest='ExpiryTest#expiryIsCorrectAcrossNanoTimeOverflow'

# zero -Xlint:all warnings (the root pom turns them all on)
mvn -pl 17-lru-lfu-cache clean compile test-compile 2>&1 | rg -i warn          # prints nothing
```

## Measured

Setup: Apple Silicon (aarch64), OpenJDK 21.0.8, Maven offline (`-o`), warm `~/.m2`, 3 consecutive runs after the review fixes.

| What | Result |
|---|---|
| Tests | **71**, 0 failures, green 3 runs in a row |
| `mvn -o -q -pl 17-lru-lfu-cache test`, wall clock (`/usr/bin/time -p`) | 2.88 / 2.96 / 3.15 s |
| Maven "Total time" | ~2.0 s |
| `DifferentialTest` (10 cases, 1M ops, invariants after each op) | ~0.86 s |
| `ConcurrencyTest` (2 cases, 800k ops) | ~0.14 s |
| Everything else | < 0.1 s per class |

```
Tests run: 10  DifferentialTest       0.861 s
Tests run: 2   ConcurrencyTest        0.142 s
Tests run: 13  CacheContractTest      0.018 s
Tests run: 3   LruBehaviourTest       0.002 s
Tests run: 4   LfuBehaviourTest       0.003 s
Tests run: 4   MinFreqCounterLfuTest  0.085 s
Tests run: 12  WeightTest             0.010 s
Tests run: 6   RemovalListenerTest    0.020 s
Tests run: 2   PolicyTradeoffTest     0.002 s
Tests run: 15  ExpiryTest             0.012 s
Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
```

These are test-suite timings, **not a throughput benchmark**. There's no JMH harness, so the README quotes no ops/sec.

Expected noise: `aThrowingErrorHandlerIsContainedToo` prints 3 `WARNING: removal error handler threw for key …` lines with stack traces. That is the JDK `System.Logger` fallback doing its job, not a failure.

## Mutation checks (manual, each reverted)

A *mutant* is a deliberate small bug put into the code to check that a test notices it. Each mutant was applied with `perl`, the named tests were run, and the file was restored byte-for-byte (`cmp`). "Before" means the tests as first shipped (61). "After" means the current suite.

| Mutant | Before | After |
|---|---|---|
| `BoundedCache.get` without `lock()`/`unlock()` | killed 3/3 | — |
| `get` under `ReentrantReadWriteLock.readLock()` | — | killed 5/5 by `ConcurrencyTest` |
| `isExpired` as naive `now >= expiresAt` | **survived** | killed: `expiryIsCorrectAcrossNanoTimeOverflow` (first `get`, before the ticker wraps) |
| `LfuPolicy.detach` never unlinks an empty bucket | **survived** (61/61 green) | killed in DifferentialTest, ConcurrencyTest, CacheContractTest, LfuBehaviourTest (`empty bucket left linked`) |
| One try/catch around the whole dispatch loop | **survived** | killed: `aThrowingListenerDoesNotCostTheOtherEventsOfTheSameOperation` (`[a]` delivered, not `[a, b, c]`) |

`readLock()` mutant, typical failures:
```
conservationLawsHoldUnderContention:93 expected: <199644> but was: <199574>     hits + misses != gets (lost stat updates)
IllegalStateException: over bound with nothing to evict: 16
NullPointerException: Cannot read field "next" because "from" is null          LFU bucket list corrupted
```

Bugs the review found, with a test that failed on the old code before the fix:

| Bug | Failing test on the old code |
|---|---|
| `total + w > max` overflows near `Long.MAX_VALUE`; nothing evicted | `anInsertNearLongMaxValueStillEvicts`, `aGrowingUpdateNearLongMaxValueStillEvicts`: `expected [b] but was [a, b]` |
| An expired victim reported as `SIZE` | `anExpiredVictimIsReportedAsExpiredNotSize`: `cause=SIZE` |
| A throwing `onError` escapes into `put` and drops the remaining events | `aThrowingErrorHandlerIsContainedToo`: `IllegalStateException: handler bug` |
