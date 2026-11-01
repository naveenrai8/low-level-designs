# Snake and Ladder: commands

Run everything from `low-level-designs/`, with a JDK 21+.

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -q -pl 19-turn-based-game-engine/snake-and-ladder clean test        # 82 tests
mvn -q -pl 19-turn-based-game-engine/snake-and-ladder test -Dtest=MarkovVsMonteCarloTest
```

## Measured

Setup: Apple Silicon (aarch64), OpenJDK 21.0.8, Maven offline, 3 consecutive runs.

| What | Time |
|---|---|
| `clean test`, whole module (Maven startup + compile + 82 tests) | 4.18 / 4.23 / 4.15 s wall |
| `MarkovVsMonteCarloTest` (4 × 200k seeded games + 1 cap test) | 1.49 s |
| Every other test class | < 0.1 s each |

The first version of `Simulation` rebuilt the `Game` per game. The O(N·F) board check then cost 1.7 s of the 1.9 s for 200k games. Now the builder memoises the check and is reused. A change of finish rule, turn rules or dice re-arms it, and each has a test.

## Monte Carlo vs Markov output

`MarkovVsMonteCarloTest` prints this (seeds 1–4, 200k games each, d6, one player):

```
markov=36.193070 mc=36.2022 se=0.0530 z=+0.17 longest=331     DataGenetics, OVERSHOOT_WINS
markov=36.193070 mc=36.1404 se=0.0528 z=-1.00 longest=266     + extra turn on six (rolls unchanged)
forfeit: mc rolls=36.7903 se=0.0541 turns=31.0152 vs no-forfeit markov 36.1931
markov=19.927070 mc=19.9434 se=0.0366 z=+0.45 longest=198     thirtySquare, BOUNCE_BACK
```

(The comments on the right are added here; the test prints only the left part.)

## Computed expected rolls per FinishRule

Exact (Markov, Gaussian elimination), d6, one player, `TurnRules.NONE`:

| Board | `OVERSHOOT_WINS` | `EXACT_OR_STAY` | `BOUNCE_BACK` | Min rolls (all rules) |
|---|---|---|---|---|
| `Boards.dataGenetics()` (100 squares, 9 ladders, 10 snakes) | 36.193070 | 39.598366 | 43.739643 | 7 |
| `Boards.thirtySquare()` (30 squares, 4 + 4) | 9.499126 | 14.417600 | 19.927070 | 3 |

The Markov system has n = 81 unknowns on the DataGenetics board under every rule: squares 0..99 minus the 19 jump starts, all reachable (asserted).

DataGenetics distribution under `OVERSHOOT_WINS` (computed): mode 20, P(7 rolls) = 0.001972, P(≤ 100) = 0.97641, P(≤ 29) = 0.497462, P(≤ 30) = 0.518490 (so the median is 30), SD 23.733.

Reproduce it in jshell after `mvn ... test`:

```bash
$JAVA_HOME/bin/jshell -q --class-path 19-turn-based-game-engine/snake-and-ladder/target/classes
```
```java
import com.lld.snakeladder.*;
var b = Boards.dataGenetics();
for (var r : FinishRule.values()) System.out.printf("%-15s min=%d E=%.6f%n", r, BoardAnalysis.minRolls(b, r, 6).getAsInt(), BoardAnalysis.expectedRolls(b, r, 6));
```
```
OVERSHOOT_WINS  min=7 E=36.193070
EXACT_OR_STAY   min=7 E=39.598366
BOUNCE_BACK     min=7 E=43.739643
```

## The 200M-game check on the sourced mean

DataGenetics reports 36,203,113,327 rolls over 1e9 games, which is 36.2031. That is 0.0100 above Markov, about 13 standard errors at 1e9 games. To rule out our solver, a standalone direct MC (a plain loop over the same board, `SplittableRandom(1)`, not part of the module) was run once:

```
games=200000000 mean=36.19265 se=0.00168        (23 s user time)
```

That agrees with Markov's 36.193070 (z = −0.25). The tests therefore assert the computed value exactly, and "36.2" only as a rounded source (±0.05). See README §3 Findings.
