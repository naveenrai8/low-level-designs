# Topic 35: Order-Matching Engine, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 35-order-matching-engine test                                  # 46 tests, about 3 seconds; no output means all passed
mvn -o -q -pl 35-order-matching-engine test -Dtest=OrderTypesTest            # market, IOC, FOK (snapshot unchanged after a kill)
mvn -o -q -pl 35-order-matching-engine test -Dtest=ReplayAndDifferentialTest # replace rules, replay, naive reference
mvn -o -q -pl 35-order-matching-engine test -Dtest=EngineTest                # 8 producers behind a start gate vs sequential replay
```

`src/test/resources/junit-platform.properties` gives every test a 10 second timeout and runs it in a separate thread (`SEPARATE_THREAD`), because in the default mode a busy loop in the test thread is never interrupted and the run hangs.

## 2. Gates

```bash
mvn -o -pl 35-order-matching-engine test-compile | rg WARNING                # only the JDK agent notice, no javac warning
python3 scripts/check_style.py 35-order-matching-engine                      # 0 problem(s)
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now' 35-order-matching-engine/src   # nothing
```

## 3. Throughput measurement (opt-in, off by default)

```bash
mvn -o -q -pl 35-order-matching-engine test -Dmatching.bench=true -Dtest=ThroughputBenchmarkTest
```

Setup: Apple M3 Max, OpenJDK 21.0.8 (aarch64), default heap, one seeded flow of 500,000 commands (60% limit, 8% IOC, 6% FOK, 6% market, 20% cancel/replace; prices 95..105, quantities 1..12), 3 warm-up runs, then 5 timed runs, median shown. One producer thread. This measures this toy on a shallow book, not an exchange.

```
direct book, one thread: median 5955988 cmds/s (min 5662250, max 7494871)
via queue + engine thread, 1 producer: median 3296160 cmds/s (min 2824342, max 3425677)
```

The queue and future handoff costs roughly half of the raw matching speed. The spread across the 5 timed runs is 5.66M to 7.49M for the bare book and 2.82M to 3.43M through the engine, so treat any single digit as noise.

## 4. Mutation checks done while writing

Each change was made by hand to `LimitOrderBook` or `MatchingEngine`, the full suite was run, and the file was restored. Some rows come from the coordinator's review of the first version, whose surviving mutations (S1 to S8) now have killing tests.

| Change | Tests that failed |
|---|---|
| Trade at the incoming price instead of the maker's | `readmeExample`, `tradesAtTheRestingPriceNotTheIncomingPrice`, `replaceThatCrossesTrades`, `matchesTheNaiveReference` |
| `crosses` uses strict `<` / `>` | `readmeExample`, `invariantsHoldAfterEveryCommand`, `matchesTheNaiveReference`, many more |
| FOK pre-check replaced by `false` | `fokThatCannotFillLeavesTheBookUnchanged`, `fokRespectsItsLimitPrice`, `randomKilledFoksNeverTouchTheBook` |
| Replace keeps priority when quantity grows | `replaceBiggerLosesPriority`, `matchesTheNaiveReference` |
| Cancel forgets `o.cancelled += gone` | `cancelRemovesTheRemainder`, `invariantsHoldAfterEveryCommand` |
| Level walked back to front (LIFO) | `fifoWithinALevel`, `readmeExample`, `replaceAfterPartialFill` |
| Maker loses one extra unit per fill | `invariantsHoldAfterEveryCommand`, `cancelRemovesTheRemainder`, `matchesTheNaiveReference` |
| S1: delete `m.live = false` when a maker fills | `cancelOfAFilledOrderIsRejected`, `replaceOfAFilledOrderIsRejected`, `invariantsHoldAfterEveryCommand` (the checker's live-flag rule), `readmeExample` |
| S2: delete `o.live = false` in `removeFromBook` | `doubleCancelIsRejected`, `replaceThatFullyFillsLeavesNothingResting`, `invariantsHoldAfterEveryCommand` |
| S3: Replace (rest-only branch) returns `0` for cancelled | `replaceSmallerKeepsPriority` |
| S3b: Replace (crossing branch) returns `0` for cancelled | `replaceShrinkWithPriceChangeReportsCancelled` |
| S4: `Math.addExact(o.original, growth)` becomes `+` | `replaceGrowthOverflowIsRejected` |
| S5: FOK pre-check uses `total + m.resting` (can overflow) | `fokWithNearMaxQuantityFillsCleanly` |
| M1: `orders.put` before the FOK pre-check | no test failed, because with the overflow-free pre-check nothing can throw there any more; the order is kept below the checks as a safety rule |
| S6: engine catches only `IllegalArgumentException` | `unexpectedExceptionFailsOnlyThatFuture` |
| S8: `close()` does not join the engine thread | `closeDrainsTheQueue` (thread must be dead), `bookIsOffLimitsUntilClosed`, `rejectionIsInTheFutureAndEngineSurvives`, `concurrentProducersEqualSequentialReplayOfTheLog`, `unexpectedExceptionFailsOnlyThatFuture` |
| M3: `close()` returns on interrupt instead of finishing | `closeWhileInterruptedStillStopsTheEngine` |
| Emptied price level is never removed | the run loops forever; the 10 s `SEPARATE_THREAD` timeout fails the matching tests (checked: the first tests to time out were `replayTestIsNotVacuous`, `matchesTheNaiveReference`, `replayReproducesTheBookAndTheTrades`); each takes 10 s, so the mutated run is slow |
