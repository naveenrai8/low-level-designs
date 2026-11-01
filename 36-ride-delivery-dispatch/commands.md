# Topic 36: Ride/Delivery Dispatch, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 36-ride-delivery-dispatch test                              # all 66 tests, about 1 s of test time (about 3 s wall with Maven, JDK 21.0.8, Apple Silicon, 3 runs)
mvn -o -q -pl 36-ride-delivery-dispatch test -Dtest=GridIndexTest         # ring search, wrong-rule defect, 4000 differential worlds, exhaustive stop bound
mvn -o -q -pl 36-ride-delivery-dispatch test -Dtest=BatchTest             # greedy vs optimal, window, strategies, pinned simulation table
mvn -o -q -pl 36-ride-delivery-dispatch test -Dtest=DriverFlowTest        # state table, timeouts, stale accept, double-offer race, cancel, expiry
mvn -o -q -pl 36-ride-delivery-dispatch test -Dtest=SurgeTest             # monotone, cap, rounding, locked quote
```

`-q` means quiet: no output means every test passed, except the three `SIM ...` lines that `BatchTest.simulationTable` prints (and pins). The numbers are mean / p95 pickup metres, then mean / p95 rider wait, averaged over seeds 1 to 10:

```
SIM drivers=100 trip=30 | 715/1629 m, 0/0 s | 711/1662 m, 3/5 s | 710/1638 m, 3/5 s
SIM drivers=60 trip=60 | 2684/6982 m, 3/15 s | 2097/6081 m, 7/26 s | 2004/5054 m, 7/25 s
SIM drivers=40 trip=60 | 1311/4050 m, 61/213 s | 1221/3749 m, 66/219 s | 1212/3718 m, 66/219 s
```

(columns: immediate greedy | batched greedy W=5 s | batched optimal W=5 s). No test sleeps: time is a `ManualScheduler`, and the race tests wait on a thread state (BLOCKED) or a hook.

## 2. Gates

```bash
mvn -o -pl 36-ride-delivery-dispatch test-compile | rg WARNING    # prints nothing
python3 scripts/check_style.py 36-ride-delivery-dispatch          # 0 problem(s)
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now' 36-ride-delivery-dispatch/src   # prints nothing
```

## 3. Mutation checks

Each change was made by hand, the suite was run twice, it failed both times, and the code was restored. The first six are the original set; the rest were added after review found mutations that survived.

| Change | Test that failed |
|---|---|
| Ring stop rule loosened (`lower*lower*4`) | `differentialAgainstBruteForce` |
| `Cell.of` uses `/` instead of `floorDiv` | `differentialAgainstBruteForce`, `lowerBoundIsSafeAndTightForEveryOffset` |
| Tie-break `id < best.id` flipped to `>` | `differentialAgainstBruteForce`, `tieExactlyAtTheBoundKeepsSearching` |
| `tryOffer` skips the AVAILABLE check | `offerAndAnswerRefusalsChangeNothing`, `safeOfferLetsExactlyOneWin` |
| Accept deadline `>=` becomes `+1 >=` | `acceptJustBeforeTheDeadlineWorksAndAtTheDeadlineIsStale` |
| Surge cap `min(cap, ...)` becomes `min(cap*2, ...)` | `surgeIsCapped` |
| Bound `size - ox` becomes `size - ox + 1` | `lowerBoundIsSafeAndTightForEveryOffset` |
| Stop test `<` becomes `<=` (tie at the bound) | `tieExactlyAtTheBoundKeepsSearching` |
| Delete the stop `break` (grid becomes a full scan) | `searchStopsEarlyAndNeverVisitsAFarCrowd` |
| Delete `cells.remove(c)` | `emptyCellsAreDropped` |
| `BatchMatcher.solve`: `!allowed` becomes `false` | `declinerIsNeverUsedEvenViaAnotherRequestsCandidates` |
| Surge demand counts only PENDING requests | `offeredRequestsCountAsSurgeDemand` |
| Accept without cancelling timers | `acceptLeavesNoTimersBehind` |
| `Fleet.expire` ignores the deadline | `earlyExpireDoesNotRevokeALiveOffer` |
| `DriverState` AVAILABLE edge changed to ON_TRIP | `everyStatePairMatchesTheHandWrittenLegalList`, `fleetApiRefusesEveryIllegalMoveFromEveryState` |
| Dispatcher does not re-arm an early timer | `timerThatFiresEarlyIsRearmed` |
| Dispatcher skips the own-offer check | `oneDispatcherCannotAnswerAnotherOnesOffer` |
| `Point` uses `Math.abs` | `minValueCoordinateIsRefused` |
| `isqrtCeil` uses `(x + 1) / 2` | `integerSqrtOfLongMaxIsExact` |
| `advance` accepts negative time | `schedulerRejectsNegativeAdvance` |
