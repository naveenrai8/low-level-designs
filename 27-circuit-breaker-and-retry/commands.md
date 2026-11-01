# Topic 27: Circuit Breaker and Retry, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 27-circuit-breaker-and-retry test                                        # all 54 tests, about 2 seconds wall time
mvn -o -q -pl 27-circuit-breaker-and-retry test -Dtest=CircuitBreakerTest              # the state machine and window boundaries
mvn -o -q -pl 27-circuit-breaker-and-retry test -Dtest=HalfOpenConcurrencyTest         # exactly N probes, and the forced check-then-act race
mvn -o -q -pl 27-circuit-breaker-and-retry test -Dtest=RetryTest                       # budget, seeded jitter, composition
mvn -o -q -pl 27-circuit-breaker-and-retry test -Dtest=BulkheadTest                    # the cap and permit release
```

`-q` means quiet: no output means every test passed. Every threaded test has a JUnit timeout, and `src/test/resources/junit-platform.properties` sets a 10 second default for all tests.

## 2. Gates

```bash
mvn -o -pl 27-circuit-breaker-and-retry test-compile | rg WARNING    # prints nothing
python3 scripts/check_style.py 27-circuit-breaker-and-retry          # 0 problem(s)
```

## 3. Mutation checks done while writing

Each change was made by hand, the tests were run, and the code was restored.

| Change | Test that failed |
|---|---|
| Skip the generation check in `report` | `staleSuccessCannotCloseAReopenedBreaker`, `staleFailureCannotReopenAClosedBreaker` |
| Compare-and-set in `ProbeGate` replaced with a blind write | `realGateGivesTheLastSlotToExactlyOne` |
| `>=` to `>` on the open-wait check | `waitBoundaryIsExact` and others |
| Remove `slots.release()` in `Bulkhead` | `permitIsReleasedWhenTheCallThrows`, `neverMoreThanTheCapRunAtOnce` |
| Disable the HALF_OPEN max-wait | `lostProbesDoNotWedgeHalfOpen` |
| Let a throwing predicate escape `call` | `throwingPredicateDoesNotLeakASlot` |
| Delete the breaker/bulkhead refusal catch in `Retry` | `openBreakerRejectionIsNotRetried`, `bulkheadRejectionIsNotRetried`, `breakerTrippingMidRetryStopsTheLoop` |
| Delete the interrupt catch in `Retry` | `interruptFromTheCallIsNotRetriedAndFlagIsRestored` |
| Count `InterruptedException` as a failure in the breaker | `interruptedCallsAreNotFailures` |
| Delete `probes.reset()` | `probeSlotsResetInEachHalfOpenPeriod` and others |
| Delete `halfOpenSuccesses = 0` | `probeSuccessesDoNotCarryOverBetweenPeriods` |
| Delete `probes.release()` on no-verdict | `ignoredProbeReturnsItsSlot` |
| Move the generation check after the no-verdict branch | `staleIgnoreCannotFreeASlotInANewPeriod` |
| Clamp `ProbeGate.release()` at 0 | `releaseWithoutAcquireIsRejected` |

Not killable by design: removing `synchronized` from `CircuitBreaker.acquire` alone, because `ProbeGate`'s compare-and-set still bounds the count (the README says so).
