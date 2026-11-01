# Topic 19: Snake and Ladder

Snake and ladder looks like the easiest LLD prompt: there is a board, some jumps, and a die. A "jump" is a ladder (moves you up) or a snake (moves you down). There are no player decisions, so the design lives entirely in the edges. Can a token overshoot 100? Can a ladder start on square 1? Can a snake land on a ladder? Can the game never end? How do you test code whose only input is a random number? A staff answer makes the dice a seam, validates the board up front, and then *proves* the expected game length two ways.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### What the interviewer is really probing

- **Testability of randomness.** Can you make a die a seam, so every test picks its rolls?
- **Validation as design.** Bad boards should be impossible to build, not runtime surprises.
- **Rule variation.** "Exact roll to finish" and "three sixes" are the standard follow-ups. Do they slot in, or do they fork `takeTurn`?
- **Knowing the maths exists.** "How long is an average game?" has an exact answer (a Markov chain). Simulation is the cross-check, not the answer.

### v1 requirements (say them out loud)

1. An N-square board with ladders (up) and snakes (down). It's validated at build time.
2. 1+ players, in a fixed turn order. Tokens start **off the board on square 0**.
3. One die roll per turn. Landing on a jump start moves you to its end.
4. The first player to reach the last square wins (v1 rule: reaching **or passing** it wins).
5. Every turn is recorded (roll, from, landed, to), so a game can be replayed.

### Clarifying questions (assumed answers)

| Question | Assumed answer | Why it matters |
|---|---|---|
| Overshoot the last square? | v1: wins. F1: exact-or-stay, bounce-back | Up to +7.5 rolls on the mean (table in §2) |
| Can a jump end on another jump's start (a chain)? | No, rejected at build | Chains are the only way to get a jump cycle |
| Can a ladder start on square 1? | Yes, tokens start on 0 | The sourced board has 1→38 |
| Can two tokens share a square? | Yes, no capture | Capture would be a different game |
| Is the board guaranteed winnable? | Checked at build, per finish rule and turn rules | A ladder can hide a trap; so can the forfeit (§3) |
| Real-time or turn-based? | Turn-based, one driver loop | Per-game confinement, no locks |

### Explicitly out of scope for v1

UI, networking, persistence, turn timers, multiple dice, and player choice (e.g. "move either of two tokens"). Each is a follow-up, not v1.

## 2. How It Solves It

### Entities and invariants

| Entity | Invariant (enforced where) |
|---|---|
| `Jump(from, to)` | `from != to` (record constructor) |
| `Board` | `last >= 2`. Every jump lies within 1..last. Ladders go up and snakes go down. No jump starts on `last`, starts are unique, and there are **no chains** (`Board.Builder.build`) |
| `Game` | ≥1 player, unique names, dice required, **no reachable square that can't finish** under the finish rule *and* turn rules (`Game.Builder.build`) |
| `TurnRules` | A forfeit implies an extra turn on a six (record constructor) |
| `TurnResult` | An immutable `List<Step>`. `end` is the square after the turn, or `start` if the turn was forfeited |

### Public interface first

```java
Board board = Board.builder(100).ladder(1, 38).snake(16, 6) /* ... */ .build();  // IAE on bad boards
Game game = Game.builder(board).players("ann", "bo")
        .dice(new FairDice(new SplittableRandom(seed), 6))   // or ScriptedDice in tests
        .finishRule(FinishRule.EXACT_OR_STAY)                // default OVERSHOOT_WINS
        .turnRules(new TurnRules(true, true))                // default NONE
        .build();                                            // IAE if a player could get stuck (rules included)
TurnResult t = game.takeTurn();   // ISE after the game is over, or if the dice break their contract
game.winner(); game.positions(); game.history();
```

### Class diagram

```
           Game.Builder ──build()──► Game ─────────────► List<TurnResult> (event log)
                │                   │  │                      └─ List<Step(roll, from, landed, to)>
                │ stuckSquare()     │  └──► Dice ◄── FairDice(RandomGenerator) | ScriptedDice(int...)
                ▼                   ├──► FinishRule  enum: OVERSHOOT_WINS | EXACT_OR_STAY | BOUNCE_BACK
           BoardAnalysis            ├──► TurnRules   record(extraTurnOnSix, threeSixesForfeit)
           (BFS, Markov, CDF)       └──► Board ──► Map<start, Jump>
                ▲
           Simulation ──plays──► Game (one player, seeded dice, turn cap)
```

### Sequence: one `takeTurn`

```
start = pos[p]; at = start; sixes = 0
loop:
  roll = dice.roll()                         ── ISE if not in 1..faces
  if roll == faces: sixes++
  if forfeit rule && sixes == 3:             ── record Step(roll, at, at, at); at = start; stop
  landed = finishRule.land(at, roll, last)   ── the overshoot rule, before any jump
  at = board.destination(landed)             ── at most one hop (no chains)
  if at == last: won; stop
  if !(roll == faces && extra-turn rule): stop
pos[p] = at; if won: winner = p, else advance the turn
```

**Worked example: one turn on a 10-square board.** The board has a ladder 3→7 and a snake 9→2. Rules: overshoot wins, a six gives an extra roll, three sixes cancel the turn. The die is a 6-sided "scripted" die that returns 6, 3 in that order. The token starts the turn on square 0.

| Roll | Top face? | Token before | Landed (finish rule) | After jump | What happens next |
|---|---|---|---|---|---|
| 6 | yes (sixes = 1) | 0 | 6 | 6 (no jump on 6) | a six, so roll again |
| 3 | no | 6 | 9 | 2 (snake 9→2) | not a six, so the turn ends on square 2 |

The turn is logged as two steps: (6, 0, 6, 6) and (3, 6, 9, 2). "Landed" is where the finish rule put the token. "To" is where it ends after the jump. If the second roll had been 6 and then another 6, the third six would cancel the turn and the token would go back to square 0.

**Worked example: the finish rule with 10 squares and a token on square 8.** "Landed" is before any jump.

| Roll | `OVERSHOOT_WINS` | `EXACT_OR_STAY` | `BOUNCE_BACK` |
|---|---|---|---|
| 2 | lands on 10, wins | lands on 10, wins | lands on 10, wins |
| 4 (target 12) | min(12, 10) = 10, wins | 12 is past the end, stays on 8 | 2×10 − 12 = 8 (walks 2 back), then a jump on 8 would apply |

**Bounce applies a jump, stay doesn't.** A bounce lands on a new square, which may be a snake head. A "stay" leaves the token where it rests, and a resting square is never a jump start.

### The algorithms

| Question | Method | Cost | Code |
|---|---|---|---|
| Shortest game | BFS (breadth-first search: visit squares by fewest rolls) over squares | O(N·F) | `BoardAnalysis.minRolls` |
| Can every game end? | Forward reachability, then reverse BFS from `last`. With the forfeit, one edge = one whole turn | O(N·F), or O(N·F³) with the forfeit | `BoardAnalysis.stuckSquare` |
| Mean game length | Absorbing Markov chain (a model where each square leads to the next with fixed chances, and the last square ends the game) `(I − Q)t = 1`, Gaussian elimination | O(n³), n = reachable resting squares (81 here, measured and asserted) | `BoardAnalysis.expectedRolls` |
| Length distribution | Push the probability vector one roll at a time | O(K·N·F) | `BoardAnalysis.finishProbabilities` |
| Six rules | Seeded Monte Carlo through the real `Game` | O(games × length) | `Simulation.run` |

Worked example of the mean: on a 2-square board with a 2-sided die, from square 0 a roll of 2 wins, and a roll of 1 moves to square 1. From square 1 any roll wins. Let t0 and t1 be the expected rolls from each square. Then t1 = 1 and t0 = 1 + 0.5·t1 + 0.5·0 = 1.5. The matrix solve does the same thing for all 81 squares at once.

Results, computed by this code on a d6 (a normal 6-sided die) with one player (the test asserts each to 1e-6):

| Board | Rule | Min rolls | Expected rolls |
|---|---|---|---|
| DataGenetics, 100 squares | `OVERSHOOT_WINS` | 7 | **36.193070** |
| | `EXACT_OR_STAY` | 7 | 39.598366 |
| | `BOUNCE_BACK` | 7 | 43.739643 |
| `thirtySquare` | `OVERSHOOT_WINS` | 3 | 9.499126 |
| | `EXACT_OR_STAY` | 3 | 14.417600 |
| | `BOUNCE_BACK` | 3 | 19.927070 |

### Concurrency model

**None, by design.** A game has one driver loop and no player decisions. A server hosting many games confines each game to one thread, or serialises its turns behind a per-game lock (the [tic-tac-toe](../tic-tac-toe/) registry). `Board` is immutable and is safe to share across games.

### Code and tests

| File | What it shows | Tested by |
|---|---|---|
| `Board`, `Jump`, `Boards` | Builder validation, no chains, the sourced board | `BoardValidationTest` (15) |
| `Game`, `TurnResult`, `Step` | Turn loop, rotation, the event log | `GameTest` (28) |
| `FinishRule` | Enum with behaviour: 3 one-line rules | `GameTest.FinishRules`, hand-checked boards |
| `TurnRules` | Extra turn and forfeit, including win-before-forfeit | `GameTest.SixRules` |
| `Dice`, `FairDice`, `ScriptedDice` | The randomness seam | `GameTest.Validation`, `ReplayTest` (2) |
| `BoardAnalysis` | BFS, trap detection, Markov, CDF | `BoardAnalysisTest` (32) |
| `Simulation`, `SimulationResult` | MC with a cap and standard error | `MarkovVsMonteCarloTest` (5) |

82 tests. `mvn -o -q -pl 19-turn-based-game-engine/snake-and-ladder clean test` takes about 4.2 s wall (see [`commands.md`](commands.md)).

## 3. The Gaps

### Follow-up rounds

**F1: "Now you must land exactly on 100." / "Also support bounce-back."**

- Delta: `FinishRule` is an enum with one abstract `land(from, roll, last)` and three one-line bodies. It's not a Strategy interface: three fixed variants gain nothing from open extension ([Topic 05](../../05-patterns-that-matter/)).
- The trap: "winnable" now depends on the rule. Bounce-back needs `faces <= last`, or a bounce can land below square 1. The build-time check runs per rule.
- Board variation (custom size, random generation) is the `Board.Builder` plus the same check.

**F2: "A six gives another roll. Three sixes cancel the turn." / "Replay a game from the log."**

- Delta: `TurnRules` plus a loop in `takeTurn`. A forfeit returns to **the turn's start square**, and the forfeiting roll is logged as a no-move `Step`, so replay sees every roll.
- Edge: a win on the 1st or 2nd six ends the turn and the game before a forfeit can happen (tested).
- The trap: the forfeit can make a board unwinnable. `TestBoards.sixesWall` puts snakes back to 1 on 2–6, 8–12 and 14–18, so the only way past is 6, 6, 6 from square 1. That's fine one roll at a time, but under the forfeit three sixes in one turn cancel themselves. So `stuckSquare` searches whole turns (≤ F³ roll sequences) when the forfeit is on, and `build()` rejects that board.
- Replay is free: the event log's rolls fed into `ScriptedDice` reproduce `history()` exactly (`ReplayTest`). Determinism comes from the dice seam, not from mocking.

**F3: "How long is an average game?" / "Is this board fair?" / "Two players?"**

- Shortest game: BFS (the same shape as [LeetCode 909](https://leetcode.com/problems/snakes-and-ladders/)).
- Mean: the absorbing Markov chain gives an exact answer in milliseconds. 200k simulated games agree within 4 SE on two boards (z = +0.17 and +0.45).
- The six rules make the next state depend on the sixes so far this turn. The exact mean expands the state to (square, sixes ∈ {0,1,2}). That's left as an exercise; the code measures it by simulation instead (only the *winnability* check models the forfeit exactly, per turn). - The extra turn alone changes **turns, not rolls**, for one player: z = −1.00 against Markov. - Forfeit measured 36.79 ± 0.05 rolls, against 36.19 without it.
- Tuning a board for fairness means editing jumps until `expectedRolls` hits a target, with `stuckSquare` as the guard.
- Two players: sourced only. Wikipedia reports **47.76 moves** with a **50.9%** first-player win, for the Milton Bradley board. This code does not reproduce it.

### Findings (CLAUDE.md rule 7: the claim follows the test)

| Claim (DataGenetics, 1e9 games) | Computed here | Verdict |
|---|---|---|
| Min 7 rolls, ~2 per 1000 games | 7; P(7) = 0.001972 | ✅ |
| Mode 20 | 20 | ✅ |
| 97.6% finish within 100 rolls | 0.97641 | ✅ |
| Mean "approx 36.2" | 36.193070 (Markov, matching the distribution's mean to 1e-6) | ✅ rounded |
| Raw total 36,203,113,327 rolls ⇒ 36.2031 | 0.0100 below, about 13 of their SEs | ⚠️ their raw total looks high. A direct 200M-game MC (`SplittableRandom`) gave 36.19265 ± 0.00168, which agrees with Markov. The cause (their RNG or counting) can't be checked without their code |
| Median 29 | **30**: P(≤29) = 0.4975, P(≤30) = 0.5185 | ❌ smallest n with CDF ≥ 0.5 is 30. The test asserts 30 |

**Labelled comparison, not asserted:** Wikipedia cites 35.8 spins (overshoot) and 39.2 (exact) for Milton Bradley's *Chutes and Ladders*: "19 chutes and ladders". The DataGenetics board also has 19 jumps but gives 36.19 / 39.60. The boards are not confirmed identical, so the ~0.4 gap is unexplained.

### Myth → Reality

| Myth | Reality |
|---|---|
| "`minRolls` exists, so the board is winnable" | `TestBoards.TRAP`: the shortest game is 2 rolls, yet half of all games never end. Check `stuckSquare` |
| "A simulation doesn't need a cap" | A buggy or trapped board hangs it. Cap the turns and **count** capped games; never drop them silently |
| "Chains are a fun feature" | Chains are the only way to get a jump cycle (5→9, 9→5). Forbid them, or resolve with a visited set and reject cycles |
| "A ladder can't start on square 1" | Tokens start on 0, so 1 is reachable (the sourced board has 1→38) |
| "Test randomness with `Math.random` and a big N" | Inject `Dice`. Assertions need `ScriptedDice`; statistics need a seed plus a standard error |
| "Simulate to get the mean" | Markov gives it exactly. MC is the cross-check: 200M games still leave ±0.0017 |
| "House rules can't break a valid board" | `sixesWall` is finishable one roll at a time, but never under the three-sixes forfeit. Check per turn |
| "Overshoot vs exact is a cosmetic choice" | +3.4 rolls for exact and +7.5 for bounce on the same board |

### When NOT to model it this way

- **Player choice** (Ludo, Monopoly): the turn needs a decision step, `Move legalMoves()`, which is the [chess](../chess/) shape. `takeTurn()` with no input stops being enough.
- **Huge or stateful boards** (portals with conditions, cards): the Markov state grows past "square", and exact solving may cost more than it's worth. Simulate.

### Production delta

Persist the event log (seed + rolls), not the positions. For fairness disputes, a server-side seeded RNG plus the logged rolls is a full audit trail.

## 4. Official References

- DataGenetics (Nick Berry, 2011), [*Mathematical Analysis of Chutes and Ladders*](https://www.datagenetics.com/blog/november12011/index.html): the board, house rule, MC and Markov results.
- Wikipedia, [*Snakes and ladders*](https://en.wikipedia.org/wiki/Snakes_and_ladders), §Mathematics: Milton Bradley 35.8 / 39.2, two-player 47.76 / 50.9%.
- Wikipedia, [*Absorbing Markov chain*](https://en.wikipedia.org/wiki/Absorbing_Markov_chain): the fundamental matrix N = (I − Q)⁻¹ and expected steps t = N·1.
- JDK 21, [`RandomGenerator`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/random/RandomGenerator.html) and [`SplittableRandom`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/SplittableRandom.html): the seeded generator behind `FairDice`.
- LeetCode, [909. Snakes and Ladders](https://leetcode.com/problems/snakes-and-ladders/): the BFS min-rolls variant.

## 5. 45-Minute Interview Timeline

| Min | Do | Say out loud |
|---|---|---|
| 0–5 | Clarify (table above); state the start on 0, the overshoot rule, no chains | "Overshoot or exact? I'll make it pluggable." |
| 5–12 | `Board.Builder` with validation; `Dice` interface | "Dice are a seam, or I can't test a single turn." |
| 12–25 | `Game.takeTurn`, `TurnResult`/`Step`, rotation, winner | "Each turn returns what happened, so the log is free." |
| 25–33 | `ScriptedDice` tests: ladder, snake, overshoot, game over | "Every roll here is chosen by the test." |
| 33–45 | F1 rule enum, F2 sixes; mention BFS/Markov for F3 | "The mean is a linear solve, not a simulation." |

If behind at minute 25, skip the six rules. Seam + validation + tested turns beat features.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Randomness | `Random` inside `Game` | `Dice` seam, scripted in tests, seeded in sims; replay from the log |
| Validation | Bounds checks | No chains (so no cycles), a winnability check per finish rule and turn rules, and knows `minRolls` ≠ winnable |
| Rules | `if (exact)` branches in `takeTurn` | One `land()` per rule; precise forfeit and bounce-jump semantics |
| Analysis | "Simulate it" | BFS + Markov exactly, MC within 4 SE, knows where Markov stops (six rules) |
| Numbers | Quotes 36 | Recomputes it, finds the median off by one, and says so |

## 7. Key Takeaways

1. The die is the only input. Make it a seam, and every turn becomes unit-testable.
2. Tokens start on **0**, off the board. Forbid jumps on the last square, and forbid **chains**.
3. "Winnable" means *no reachable square can't finish*, per finish rule and turn rules, not "a shortest path exists".
4. Bounce applies a jump; stay doesn't.
5. A forfeit returns to the turn's start, and the forfeiting roll is still logged.
6. The mean is exact: `(I − Q)t = 1`. DataGenetics board: **36.19 / 39.60 / 43.74** (overshoot / exact / bounce).
7. Simulations need a seed, a cap, and a standard error, or their numbers mean nothing.
8. Check sourced numbers. Here, the median is 30, not 29.

---

[← Tic-Tac-Toe](../tic-tac-toe/) · [Topic 19 overview](../README.md) · [Index](../../README.md) · [Chess →](../chess/)

**Self-grade: STRONG.** It has the full spine, a validated board, three finish rules and the six rules with exact semantics, replay, and BFS + Markov verified against sourced figures and seeded MC. Two findings are recorded (the median, the raw mean total). The gap: the mean under the six rules and the two-player game are measured or sourced, not solved exactly (that needs the expanded-state Markov chain). Winnability under the forfeit is exact.
