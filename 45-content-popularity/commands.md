# Topic 45 commands

Run all tests (JDK 21; the default `java` on PATH is 17):

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64
cd /Users/naveen.rai/Office/low-level-designs
mvn -o -q -pl 45-content-popularity test
mvn -o -pl 45-content-popularity test-compile | rg WARNING
python3 scripts/check_style.py 45-content-popularity
```

Count-Min error measurement (printed by `CountMinSketchTest.measuredErrorOnZipfStream`; JDK 21.0.8, seed 11, 3 runs identical):

```
CMS zipf: N=200000 width=272 depth=5 bound=2000 maxErr=980 meanErr=206.11 overBound=0/5000
```

## Mutation self-check

Each row is a one-line bug. Every mutant compiled, each was run twice, both runs failed (killed 2/2), and the file was restored and compared byte for byte after each.

| Bug | Killed by |
|---|---|
| R1 sketch late-event check `isLive(idx, idx)` | `SketchWindowedTopKTest.lateEventRejectedAndLiveBucketSurvives` |
| R2 heap skips `siftDown` after an update | `SketchWindowedTopKTest.heapResortsAfterUpdate` |
| R3a/R3b future check allows a later timestamp in the same or next bucket (exact and sketch) | `WindowedTopKTest.futureEventThrows`, `SketchWindowedTopKTest.futureEventThrows` |
| R6 `floorDiv` becomes `/` | `WindowedTopKTest.negativeTickerReadingsWork`, `specMathOnNegativesAndUpperEdge` |
| R7 `floorMod` becomes `%` | `WindowedTopKTest.specMathOnNegativesAndUpperEdge` |
| R8 `isLive` drops the upper bound | `WindowedTopKTest.specMathOnNegativesAndUpperEdge` |
| R9 prune only exact zero | `DecayingPopularityTest.tinyScoresArePruned` |
| R10 `MAX_HALF_LIVES` 64 to 1000 | `DecayingPopularityTest.rebaseHappensBeforeOverflow` |
| R11 tenant creation lock is a private object | `MultiTenantPopularityTest.racingNewTenantsCannotBothPassTheCap` (deterministic, no race luck) |
| R14 `clear()` keeps the total | `CountMinSketchTest.clearResetsEverything` |
| Remove `synchronized` from `WindowedTopK.recordAt` | `WindowedTopKTest.lockIsHeldDuringOperations` (same-thread probe, deterministic) |
| Skip the stale-slot wipe (exact) | `staleBucketIsResetAfterIdleGapLongerThanWindow` and others |
| Skip the stale-slot wipe (sketch) | `expiryAndStaleSketchReset`, `agreesWithExactVersion` |
| `isLive` off by one | `bucketBoundaryBelongsToNextBucket` and others |
| Accept events older than the window | `lateEventRule` |
| Reverse tie order | `tiesBreakByIdAscending` |
| Sketch takes max instead of min | `measuredErrorOnZipfStream`, `smallExample` |
| Width uses floor | `sizingFormulas`, `memoryIsStated` |
| Heap inserts instead of updating | `heapUpdatesInsteadOfDuplicating` |
| No decay rebase | `differentialAgainstNaiveRecompute`, `longRunDoesNotOverflow` |
| Decay sign flipped | `halvesEveryHalfLife` |
| Tenant cap off by one | `capRefusesNewTenantWithoutEvictingOthers` |
| New tenant shares another's state | `capRefusesNewTenantWithoutEvictingOthers` |
| Unknown tenant `k < 0` unchecked | `negativeKThrowsForUnknownTenantToo` |
