# Tic-Tac-Toe: commands

Run from `low-level-designs/`, with a JDK 21+ in `JAVA_HOME`.

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -q -pl 19-turn-based-game-engine/tic-tac-toe clean test     # all 55 tests
mvn -q -pl 19-turn-based-game-engine/tic-tac-toe test -Dtest=EnumerationTest
mvn -q -pl 19-turn-based-game-engine/tic-tac-toe test -Dtest=RegistryConcurrencyTest
mvn -q -pl 19-turn-based-game-engine/tic-tac-toe test -Dtest=MeasurementsTest -Dttt.measure=true   # opt-in timings
```

Add `-o` (offline) once `~/.m2` is warm.

**About `-DargLine`:** it does nothing here. The parent pom sets `<argLine>-ea</argLine>` explicitly in surefire's configuration, and explicit plugin configuration beats the `argLine` user property. I checked this with a throwaway probe test: under `-DargLine=-Dfoo=1` it printed `ea=true foo=null`, the same as without the flag. So assertions stay on, but you can't add JVM flags this way. To add one, edit the pom.

## What to expect

**55 tests, 0 failures, 0 compiler warnings (`-Xlint:all`).** Surefire reports `Tests run: 58, Skipped: 3`. The 3 skipped are `MeasurementsTest`, which runs only with `-Dttt.measure=true`, and surefire prints its one `[WARNING]` line for them. `mvn clean test` took **2.1–2.2 s** wall time over 3 consecutive runs (Apple M3 Max, JDK 21.0.8, offline). The tests themselves take under 0.8 s. The rest is Maven and compilation.

| Test class | Tests | Time (one run) |
|---|---|---|
| `GameConfigTest` | 10 | 0.01 s |
| `RejectionTest` | 11 | 0.07 s |
| `WinDetectionTest` | 12 | 0.01 s |
| `DifferentialWinTest` | 1 | 0.27–0.32 s |
| `EnumerationTest` | 1 | 0.19–0.26 s |
| `MinimaxTest` | 7 | 0.04 s |
| `UndoReplayTest` | 7 | 0.02 s |
| `RegistryConcurrencyTest` | 6 | 0.06 s |

## Printed output

`EnumerationTest` and `MinimaxTest` each print one line (real output):

```
games=255168 X=131184 O=77904 draws=46080 positions=5478 byLength=[0, 0, 0, 0, 0, 1440, 5328, 47952, 72576, 127872]
perfect vs random, 500 games per side: as X 497 W / 3 D / 0 L, as O 405 W / 95 D / 0 L
```

- `games` and `positions` are asserted against the sourced values ([Game complexity](https://en.wikipedia.org/wiki/Game_complexity)).
- The X/O/draw split and `byLength` (index = moves played) are computed, not sourced. Both are asserted, so a regression shows up.
- Odd lengths are X wins and even lengths are O wins. Length 9 also holds all 46,080 draws.
- The minimax split is deterministic (seed 42). Only "0 L" is asserted.

## Measurements (`MeasurementsTest`, opt-in)

One run of the harness (real output, Apple M3 Max, JDK 21.0.8):

```
minimax, empty board, fresh strategy per call (no memo hit): first 14.5 ms; next 20: median 3.1 ms, min 2.0, max 8.2
1,000 games vs random (seed 42), run 1: shared memo 18 ms, fresh strategy per move 1190 ms
1,000 games vs random (seed 42), run 2: shared memo 12 ms, fresh strategy per move 1249 ms
unlocked Game, 2 threads, same turn, 100,000 rounds: both accepted 37 (0.037%), a move threw 0 []; 1.5 s
```

| Over 5 runs | Range |
|---|---|
| First empty-board search | 14–18 ms |
| Median of the next 20 (each with a fresh `MinimaxStrategy`, so no memo hit) | 1.7–4.2 ms |
| 1,000 games, shared memo / fresh strategy per move | 11–19 ms / 1,136–1,270 ms |
| Unlocked race: both accepted, per 100,000 rounds | 6–149 |
| Unlocked race: a move threw | 1 run of 5 (`ArrayIndexOutOfBoundsException` from the unsynchronised history list) |

Method:
- Single wall-clock `System.nanoTime()` runs. No JMH, no forks, no error bars.
- Run the class alone, so "first" really is the JVM's first search. Methods run in a fixed order: minimax, games, race.
- The per-run race rate varies about 25×, so a 1,000-round hammer catches a missing lock about 6%–78% of the time (estimate, 1 − (1 − p)¹⁰⁰⁰). That's why `RegistryConcurrencyTest` uses a race-window hook instead.

## Mutation checks

Each row is a temporary break in `src/main`. The break survived the earlier version of the test and fails the current one. Each break was restored after the run.

| Break | Caught by |
|---|---|
| `LineCounters` lets seats {X,A}, {O,A} or {X,O} share counts (the ±1 sign trap) | `WinDetectionTest` X·A·X / O·A·O / X·O·X rows |
| `undo` skips `detector.remove` | `UndoReplayTest`: refinish after undoing a win, and replay on the same object |
| `undo` skips `winner = NONE` | `UndoReplayTest`: `winner()` is empty after undo |
| An `OCCUPIED` rejection touches the detector | `RejectionTest`: seeded playouts vs a fresh replay |
| An `OUT_OF_BOUNDS` rejection advances the turn | `RejectionTest`: `playerToMove` unchanged |
| A win scores `WIN` instead of `WIN − ply` | `MinimaxTest.prefersTheFastestForcedWin` |
| `submit` locks `this` (global) instead of the game | `RegistryConcurrencyTest.holdingOneGamesLockDoesNotBlockAnotherGame` |
| `submit` drops the lock | `RegistryConcurrencyTest.registryHoldsTheGameLockAcrossTheRaceWindow` |
| `replay` skips the configured-player check | `UndoReplayTest.replayRejectsAMoveByAnImpostorWithTheRightId` |
