# Topic 42: Workflow Engine, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
mvn -q -pl 42-workflow-engine test                                  # all 45 tests, about 2 seconds, no output means green
mvn -q -pl 42-workflow-engine test -Dtest=TransitionTest            # guards, illegal moves, the race, forced interleaving
mvn -q -pl 42-workflow-engine test -Dtest=MigrationTest             # all-or-nothing migration
mvn -q -pl 42-workflow-engine test -Dtest=AuditReplayTest           # model-vs-engine differential test and replay
python3 scripts/check_style.py 42-workflow-engine                   # expect 0 problem(s)
```

Measured on a Mac (aarch64), JDK 21.0.8: a full module test run takes about 2 seconds wall time including Maven start-up.

## Tests (45)

| Class | Count | Proves |
|---|---|---|
| `DefinitionTest` | 11 | build-time validation, immutability, copies of caller collections |
| `TransitionTest` | 12 | guards (fail closed, read-only, re-entry refused), illegal moves, version race, forced lock interleaving, error contract |
| `MigrationTest` | 5 | all-or-nothing migration, creates wait for migration, audit actor and one timestamp |
| `HookTest` | 11 | after commit, isolation (Exception, Error, rejecting executor), SLOW boundary, depth limit, stale hook cannot overwrite |
| `AuditReplayTest` | 6 | audit fields, engine equals hand-written model equals replay, corrupt-log rules one by one |

## Mutation self-check

Each row applies one one-line bug, runs the module tests, and restores the file. Every mutant was killed. The first block is the original set (first review pass); the second block is the survivors the review found, now killed.

| Bug | Killed by |
|---|---|
| Version check skipped | `racingTransitionsHaveExactlyOneWinner`, `staleVersionRejected`, `lateHookCannotOverwriteNewerChange` |
| Only the first guard checked | `failingGuardNamedAndNothingChanges`, `missingFieldFailsClosed` |
| Legality (from-state) check removed (`filter(x -> true)`) | `illegalMoveRejectedWithNoChange`; also `engineEqualsReferenceModelAndReplay` alone |
| Guard result ignored (`if (false)`) | `allGuardsMustPass`, `failingGuardNamedAndNothingChanges`; also the reference-model test alone |
| Migration skips its pre-check | `strandedIssueRejectsWholeMigration` |
| Hook depth limit off | `hookLoopStopsAtDepthLimit`, `depthCountsUpByOnePerHop` |
| Hooks never fired | `hooksRunAfterCommit`, `depthCountsUpByOnePerHop`, `errorFromHookIsContained` |
| Migration not written to audit log | `migrationAuditEntriesCarryActorAndOneTimestamp` |
| Unreachable-state check off | `unreachableStateRejected` |
| Definition exposes its mutable state set | `definitionIsImmutableAfterBuild` |
| Migration version number off by one | `completeMappingMovesEveryIssue` |
| M1 `IssueView` stops copying fields | `issueViewCopiesItsFields` |
| M2 `Transition` stops copying `from` / `guards` (two mutants) | `transitionCopiesItsInputs` |
| M3 replay ignores version gaps | `corruptLogIsRefused` |
| M4 replay ignores duplicate or late create | `corruptLogIsRefused` |
| M5 empty-source check off | `transitionWithNoSourceRejected` |
| M6 budget `>` becomes `>=` | `budgetBoundaryIsInclusive` |
| M7 migration entry time and actor replaced | `migrationAuditEntriesCarryActorAndOneTimestamp` |
| M8 create entry actor replaced | `entriesRecordWhoWhenFromToVersion` |
| M9 hook result issue id null | `resultsNameHookIssueAndTransition` |
| S1 no lock in `createIssue` | `createDuringMigrationWaitsAndUsesNewVersion` (forced, every run) |
| S2 no lock in `transition` | `secondCallerIsBlockedInsideTheCriticalSection` (forced), plus the hammer test |
| Hook catch narrowed from Throwable to Exception or LinkageError | `errorFromHookIsContained` |
| Executor rejection no longer caught | `rejectingExecutorIsContained` |
| Hook ignores the version it saw (uses ANY_VERSION) | `lateHookCannotOverwriteNewerChange` |
| Re-entry check off | `guardCallingTheEngineIsRefused` |
| `actorIsNotField` fails open | `missingFieldFailsClosed` |
