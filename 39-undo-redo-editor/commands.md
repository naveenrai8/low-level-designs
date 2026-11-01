# Topic 39: Undo/Redo Editor, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests

```bash
mvn -o -q -pl 39-undo-redo-editor test                          # 47 tests (46 run, 1 benchmark skipped), about 2 s (Apple M3 Max, JDK 21.0.8, green 3 runs in a row)
mvn -o -q -pl 39-undo-redo-editor test -Dtest=CoalescingTest    # the timeline table and each break rule
mvn -o -q -pl 39-undo-redo-editor test -Dtest=PieceTableTest    # differential test against StringBuilder
python3 scripts/check_style.py 39-undo-redo-editor              # expect "0 problem(s)"
```

No output from `mvn -q` means every test passed. Tests have a 10 s timeout on a separate thread (`src/test/resources/junit-platform.properties`) so a looping mutation fails instead of hanging.

## Benchmark (off by default)

```bash
mvn -o -pl 39-undo-redo-editor test -Deditor.bench=true -Dtest=BenchmarkTest
```

Measured on Apple M3 Max, JDK 21.0.8, 1 MB text, random two-char inserts, median of 7 runs after 3 warm-ups (ms):

```
10000 inserts: piece table 41.0 (min 40.1), StringBuilder 110.3 (min 108.1)
20000 inserts: piece table 177.7 (min 172.6), StringBuilder 230.4 (min 228.3)
40000 inserts: piece table 795.2 (min 785.1), StringBuilder 472.2 (min 469.0)
```

The flat piece list loses past about 30,000 random inserts (each insert scans all pieces). `text()` is not timed.

## Mutation checks

Each mutation is one line changed by hand (script-applied, then restored), run against the final code. A mutation is killed when a test fails.

| Mutation | Killed by |
|---|---|
| M1 new edit keeps redo (`redoStack.clear()` removed) | `newEditClearsRedo`, `undoClosesTheStep`, both differential tests |
| M2 delete stores no text (empty substring) | `backspaceRunCoalesces`, `forwardDeleteRunCoalesces`, `timelineTable`, `EditorModelTest`, `emojiIsOnePosition` |
| M3 insert skips the position check and clears redo first | `rejectedEditChangesNothing` |
| M4 pause limit exclusive (`<=` to `<`) | `pauseBreaksButBoundaryMerges`, `timelineTable` |
| M5 pause ignored | `pauseBreaksButBoundaryMerges`, `timelineTable` |
| M6 cursor jump does not break the run | `cursorJumpBreaks`, `timelineTable` |
| M7 backspace runs not merged | `backspaceRunCoalesces` |
| M8 piece table never extends the last add piece | `typingBurstKeepsPieceCountBounded` |
| M9 history cap not enforced | `capDropsOldestSteps`, `capAndRedoInteraction`, `EditorModelTest` |
| M10 undo leaves the step open (first run survived) | `undoDoesNotReopenTheStepBelow` (added after it survived) |
| M11 length counted in UTF-16 chars | `emojiBurst`, `emojiIsOnePosition`, both differential tests |
| M12 word boundary ignored | `wordBoundaryBreaks` |
| M13 delete keeps one extra char on the right | 10+ tests including `PieceTableTest.workedExample` and the differential tests |
| M14 OT tie-break ignores the site id | `OtTest.randomPairsConverge` |
| M15 redo does not push back onto undo | `EditorModelTest`, `capAndRedoInteraction`, `PieceTableTest.differentialAgainstStringBuilder` |
| M16 insert/delete mixed merge (first run survived) | `deleteRightAfterInsertEndStaysSeparate` (added after it survived) |
| S1 undo cursor set to `pos` for every command | `undoOfBackspaceRestoresCursor` |
| S2 redo cursor set to `pos` for every command | `redoOfInsertMovesCursorToEnd` |
| S3 delete elsewhere merges into a delete run (`if (true)`) | `deleteElsewhereBreaksDeleteRun`, `randomCoalescingRoundTrips` |
| S4 word rule fires after any whitespace, not only before a non-space | `wordRuleLooksAtLastCharOnly`, `randomCoalescingRoundTrips` |
| S5 pause compare written as `now <= last + pause` (breaks at nanoTime wrap) | `pauseRuleSurvivesClockWrap` |
| S6 a rejected insert restarts the pause clock | `rejectedEditDoesNotTouchCoalescingState` (and three other coalescing tests) |
| S7 a rejected insert closes the open step (crude: it also fires on valid inserts, so many tests fail) | `rejectedEditDoesNotTouchCoalescingState` among others |
| S8 piece table extends any previous piece (`prev.add()` dropped) | `randomCoalescingRoundTrips`, `OtTest.randomPairsConverge` (both fail on wrong text) |

Equivalent mutants (cannot be killed, by argument):

| Mutation | Why it is equivalent |
|---|---|
| E1 `open = false` removed from `redo` | Redo only runs after an undo or another redo, and both leave `open` false. Any new edit clears redo, so `open` is already false on entry. |
| E2 `checkPos` removed from `delete` | A bad `pos` is still rejected: by the `len` check when `pos` is past the end, and by `buffer.delete` (which throws before any record or stack change) when `pos` is negative. The outcome is the same exception and no change. |
