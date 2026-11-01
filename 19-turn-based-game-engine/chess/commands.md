# Chess: commands

Run everything from `low-level-designs/`, with a JDK 21:

```bash
export JAVA_HOME=/path/to/jdk-21
```

## Tests

| What | Command |
|---|---|
| Default suite | `mvn -q -pl 19-turn-based-game-engine/chess clean test` |
| Deep perft too | `mvn -q -pl 19-turn-based-game-engine/chess test -Dperft.deep=true` |
| One class | `mvn -q -pl 19-turn-based-game-engine/chess test -Dtest=PerftTest` |
| One test | `mvn -q -pl 19-turn-based-game-engine/chess test -Dtest='GameEndTest#foolsMate'` |

Add `-o` (offline) when `~/.m2` already holds the dependencies.

Measured on an Apple M3 Max with OpenJDK 21.0.8, offline:

| Suite | Tests | Time |
|---|---|---|
| Default (`clean test`, mvn wall) | 142: 140 run + 2 skipped (`DeepPerftTest`, gated) | 3.55 / 3.26 / 3.27 s over 3 green runs |
| Default, Surefire per class | `PerftTest` 31 in 0.14 s · `RandomGameTest` 1 in 0.47 s · the other 6 classes < 0.1 s each | – |
| Deep (`-Dperft.deep=true`) | 9 | 11.50 s in Surefire, 14.2 s mvn wall |

The slowest deep tests: start d6 8.79 s, Position 3 d6 1.02 s, Kiwipete d4 stats 0.67 s, start d5 0.44 s.

Surefire is the Maven test runner. Without the flag, it reports `DeepPerftTest` as `Tests run: 2, Skipped: 2`: JUnit counts the disabled class as skipped. With it, all 9 tests run. That shows Surefire forwards `-D` system properties to the forked test JVM.

## Perft from the command line

```bash
mvn -q -pl 19-turn-based-game-engine/chess compile
java -cp 19-turn-based-game-engine/chess/target/classes com.lld.chess.Perft \
  "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1" 5
```

The output has the same format as Stockfish's `go perft`: one `move: count` line per root move, then `Nodes searched: N`.

```
a2a3: 181046
a2a4: 217832
...
h2h4: 218829

Nodes searched: 4865609
```

Once sorted, these 20 lines are the same as the start-position d5 divide published in the [Stockfish docs](https://official-stockfish.github.io/docs/stockfish-wiki/UCI-Protocol-and-Stockfish-Commands.html).

### Throughput

Measured on an Apple M3 Max with OpenJDK 21.0.8, default JVM flags and a single thread. Each time is one `java … Perft` process, so it includes JVM start-up and JIT warm-up.

| Run | Nodes | Runs | Time: median (range) | Nodes/s: median (range) |
|---|---|---|---|---|
| start d6 | 119,060,324 | 5 | 8.72 s (8.63–8.96 s) | ≈13.7M (13.3–13.8M) |
| start d5 | 4,865,609 | 1 | 0.64 s | ≈7.6M (warm-up dominates) |
| Position 3 d6 | 11,030,083 | 1 | 1.33 s | ≈8.3M |

The board is a copy-make mailbox (see the README for both terms), and the leaves are bulk-counted, which saves one level of work: at depth 1 `count` returns the size of the legal-move list without playing the moves. Building that list still makes every pseudo-legal move once for the legality filter.

## Debugging a wrong perft count with divide

Example: you expect 8,902 at depth 3 and get 8,900. Divide shows which first move is off, and you repeat inside it.

| Step | Result |
|---|---|
| Depth 3 divide, ours vs Stockfish | Only `b1c3` differs: ours 440, theirs 442 |
| Play `b1c3`, run depth 2 on the child FEN | Only one black reply differs |
| Play that reply, run depth 1 | The diff names the one missing move |

The numbers in this table are made up to show the method. They are not a real bug. The steps:

1. Find the smallest failing depth `N` for the position.
2. Get both divides:

   ```bash
   java -cp 19-turn-based-game-engine/chess/target/classes com.lld.chess.Perft "<fen>" N \
     | rg '^[a-h][1-8][a-h][1-8][qrbn]?: ' | sort > ours.txt
   printf 'position fen <fen>\ngo perft N\nquit\n' | stockfish \
     | rg '^[a-h][1-8][a-h][1-8][qrbn]?: ' | sort > sf.txt
   diff ours.txt sf.txt
   ```

3. Take a root move whose count differs, or one that only one side has. A missing or extra move at the root is the bug itself.
4. Get the child FEN: `Game.fromFen(fen)`, `play(move)`, `position().toFen()`. Repeat with `N - 1`.
5. At `N = 1` the diff names the exact illegal or missing move. Fix the generator, never the expected count.

Stockfish isn't installed on the machine these numbers came from. The diff was checked against the divide published in the Stockfish docs instead (above).
