# Topic 43: Feature Flags, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
mvn -q -pl 43-feature-flags test                              # all 50 tests; measured 1.0 to 1.6 s Maven total time on a Mac (aarch64), JDK 21.0.8, 3 runs
mvn -q -pl 43-feature-flags test -Dtest=BucketingTest         # hash, buckets, rollout, spread (prints MEASURED line)
mvn -q -pl 43-feature-flags test -Dtest=ReloadTest            # swap in the middle of an evaluation
python3 scripts/check_style.py 43-feature-flags               # expect 0 problem(s)
```

Real output of the spread test (100,000 users, flag `checkout`, salt `s1`, JDK 21.0.8):

```
MEASURED 10%=0.1000 50%=0.5010 chi2(99df)=100.3
```

## Tests (50)

| Class | Count | Proves |
|---|---|---|
| `BucketingTest` | 11 | pinned Murmur3 answers and buckets, independence, salt, monotonic rollout, 0% and 100%, slice edges, weights sum, spread |
| `RuleTest` | 6 | first match wins, missing attribute fails closed, type mismatch, operator boundaries, AND, bad shapes |
| `PrecedenceTest` | 11 | each ladder level beats the next, unknown flag, prerequisites, cycles, dangling, depth limit |
| `ReloadTest` | 3 | no torn read across a swap, invalid config keeps old live, stale versions ignored |
| `EventTest` | 6 | event fields with fixed clock, per-user sampling, extremes, exact drop count, broken sink |
| `HardeningTest` | 13 | off prerequisite is unmet, sampling independent of rollout and exclusive at the edge, event path uses flag salt, lost apply race, notes kept, sample rate, unknown variants, key mismatch and duplicates, negative weight, bad condition values, custom Number |

## Mutation self-check

Each row applies one one-line bug, runs the module tests on JDK 21, and restores the file. Every mutant compiled (no compile-error false kills) and every mutant was killed.

| Bug | Killed by |
|---|---|
| Flag key left out of the hash | `bucketsArePinnedAcrossRuns`, `flagsAreIndependent` |
| No separator between key, salt and user | `bucketsArePinnedAcrossRuns`, `partsDoNotRunTogether` |
| `String.hashCode` instead of Murmur3 | `bucketsArePinnedAcrossRuns`, `flagsAreIndependent`, `samplingIsPerUserAndStable` |
| Murmur3 length mix removed | `murmur3MatchesPublishedAnswers`, `bucketsArePinnedAcrossRuns` |
| Slice edge `<` becomes `<=` | `sliceEdges`, `weightsMustSumToBucketCount` |
| Weights may exceed 10,000 (`!=` becomes `<`) | `weightsMustSumToBucketCount` |
| Individual targets ignored | `targetBeatsRule` |
| Kill switch ignored | `killSwitchBeatsEverything`, `prerequisiteBeatsTarget` |
| Prerequisite check ignored | `prerequisiteGatesTheDependent`, `prerequisiteBeatsTarget`, `swapInsideEvaluationIsNotSeen` |
| Prerequisite reads the live config (torn read) | `swapInsideEvaluationIsNotSeen` |
| Equal version accepted | `olderOrEqualVersionIsIgnored` |
| Older version accepted | `olderOrEqualVersionIsIgnored` |
| Sampling random per call | `samplingIsPerUserAndStable`, `sampledUserAlwaysEmitsOthersNever` |
| Sink exception escapes | `brokenSinkDoesNotBreakEvaluation` |
| Event ignores the clock | `eventCarriesAllFields` |
| Rollout skipped | `rolloutBeatsDefault`, `rolloutIsMonotonic`, `zeroAndHundredPercent` |
| Drops not counted | `fullBufferDropsAndCountsExactly` |
| Missing attribute fails open | `missingAttributeFailsClosedWithReason` |
| String compared as number | `typeMismatchIsNotAMatch` |
| Rule stops being AND | `allConditionsMustMatch` |
| Cycle check off | `cyclesAreRejected`, `invalidConfigLeavesOldLive` |
| Depth bound loosened by one | `depthIsBounded` |
| Missing prerequisite flag allowed | `danglingPrerequisitesAreRejected` |

Review round: each of the following was run twice (2/2 killed, each restored afterwards). Controls from the first set were also re-run and still die 2/2.

| Bug | Killed by |
|---|---|
| Off prerequisite counted as met (`need.reason() == OFF` check removed) | `offPrerequisiteIsUnmetEvenIfOffVariantMatches` |
| Sampling uses `salt` instead of `salt + "/sample"` | `samplingIsIndependentOfRolloutBucket`, `eventPathUsesTheFlagsSalt` |
| Sampling threshold `<` becomes `<=` | `samplingThresholdIsExclusive` |
| Event path drops the flag's salt | `eventPathUsesTheFlagsSalt` |
| Compare-and-set replaced by `getAndSet` | `losingTheApplyRaceReturnsStale` |
| Rule result drops the notes | `ruleResultKeepsEarlierNotes` |
| Rollout result drops the notes | `rolloutResultKeepsEarlierNotes` |
| Negative sample rate allowed | `badSampleRateRefused` |
| Unknown off / target / rule / rollout variant allowed (4 mutants) | `unknownVariantRefusedEverywhere` |
| Map key mismatch allowed | `configKeyMismatchAndDuplicatesRefused` |
| Duplicate flag key allowed | `configKeyMismatchAndDuplicatesRefused` |
| Weight -1 allowed | `negativeWeightRefused` |
| STARTS_WITH value type unchecked | `badConditionValuesRefused` |
| Float NaN usable | `badConditionValuesRefused` |
| Custom `Number` accepted as a number | `badConditionValuesRefused`, `customNumberAttributeIsTypeMismatch` |
