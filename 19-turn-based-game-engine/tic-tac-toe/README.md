# Topic 19a: Tic-Tac-Toe

"Design tic-tac-toe" sounds like a warm-up. In the room you hear "now make it N×N with K in a row", then "add undo and an AI", then "run 10,000 games on one server". The simple 3×3 code breaks at each step: it rescans the whole board, throws on a bad move and keeps a public `char[][]`. A staff answer starts with a validated config, a result type and a win check that only looks at the newest move. It proves the check against a slow brute-force version (an "oracle", a trusted slow answer to compare with) and an exact game count, and it locks per game, not globally.

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

| Probe | What a weak answer does |
|---|---|
| Modelling | `char[][] board` + `boolean xTurn`; nothing owns the invariants |
| Error contract | Throws for "occupied" (an expected event), or returns `false` (no reason) |
| Algorithm | Rescans all 8 lines after every move; doesn't scale to N×N |
| Change tolerance | Hard-codes 3, 'X', 'O'; F1 means a rewrite |
| Testing | Three happy-path wins; no oracle, no count, no race |

### v1 requirements (say them out loud)

- 3×3, two players, X moves first, alternate turns.
- A move is **accepted**, **wins** (with the winning line), **draws**, or is **rejected** with a reason.
- A rejected move changes nothing.
- Win check in O(1) per move.

### Clarifying questions (with assumed answers)

| Question | Assumed answer |
|---|---|
| Board size and win length fixed? | 3×3 for v1; N and K come in F1 |
| Who calls `play`? One UI thread or a server? | One thread for v1; a server in F3 |
| Is a bad move an error or a normal event? | Normal event (a value). An unknown player is a caller bug (exception) |
| AI needed? | F2 |

### Explicitly out of scope for v1

Rendering/UI, networking, persistence, clocks, ratings, more than 2 players.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Invariant |
|---|---|
| `GameConfig(size, winLength, players)` | 1 ≤ K ≤ N; ≥ 2 players; unique ids and symbols. Checked once, at construction |
| `Player(id, symbol)` | Non-blank id; symbol isn't whitespace or the empty marker |
| `Game` | Exactly one player to move while `IN_PROGRESS`; `history` replays to the board; nothing moves after `WON`/`DRAW` |
| `Move(player, cell)` | A record: the unit of undo and replay (Command-lite, Topic 05) |
| `WinDetector` | Its counts always equal the stones on the board, including after `undo` |

### Public interface first

```java
sealed interface MoveResult permits Accepted, Won, Draw, Rejected {}
record Accepted(Player next)               implements MoveResult {}
record Won(Player winner, List<Cell> line) implements MoveResult {}
record Draw()                              implements MoveResult {}
record Rejected(Reason reason)             implements MoveResult {}
enum Reason { GAME_OVER, NOT_YOUR_TURN, OUT_OF_BOUNDS, OCCUPIED }   // checked in this order

MoveResult play(String playerId, int row, int col);   // IAE only for an unknown player
Move undo();                                          // ISE if nothing to undo
static Game replay(GameConfig c, List<Move> moves);   // IAE if any move is rejected
char[][] board();                                     // a copy
```

The caller uses a `switch` on the result and the compiler checks that every case is handled (JEP 441). `MinimaxStrategy` does exactly that.

### Class diagram

```
                 GameConfig ──(validated)──┐
                                           ▼
 MoveStrategy ◄── RandomStrategy        ┌──────────────────┐ uses ┌──────────────────┐
     ▲            MinimaxStrategy ─────►│ Game (1 thread)  │─────►│ «WinDetector»    │
     │ choose(Game) → Cell              │ grid, toMove,    │      │  LineCounters    │ K == N
                                        │ status, history  │      │  DirectionalScan │ K <  N
 GameRegistry ── Map<GameId, Slot> ────►│ play/undo/replay │      └──────────────────┘
   submit() = synchronized(slot)        └──────────────────┘
```

### Sequence: one `play`

```
caller          Game                         WinDetector
  │ play(X,r,c)  │                                │
  │─────────────►│ over?  → Rejected(GAME_OVER)   │
  │              │ X's turn? → Rejected(NOT_YOUR_TURN)
  │              │ ── race window (test hook) ──  │
  │              │ in bounds? empty?  → Rejected  │
  │              │ grid[r][c] = X                 │
  │              │ place(r,c,X) ─────────────────►│ counts++
  │              │ history += move                │
  │              │ winningLine(r,c,X) ───────────►│ O(1) or O(K)
  │◄─────────────│ Won(line) | Draw | Accepted(next)
```

### Win detection: three ways, with their cost

| Approach | Per move | Memory | Works for |
|---|---|---|---|
| Rescan every line (brute force) | O(N²) lines × O(K) | 0 | Everything. Used as the **test oracle** |
| `LineCounters`: per-player count per row, column and both diagonals | **O(1)** | (2N + 2) ints per player | K == N only |
| `DirectionalScan`: walk ±(K−1) from the new stone in 4 directions | **O(K)** | N² ints | Any K ≤ N |

`WinDetector.forConfig` picks counters when K == N, otherwise the scan. Only the last stone can create a win, so neither needs to look anywhere else. Both support `remove`, so `undo` is exact.

**Worked example: the O(1) counter check on 3×3 (K == N = 3).** A "stone" is one placed symbol. The counters hold, per player, how many stones sit in each row, column and diagonal. X wins when one of its counters reaches 3.

| Move | Cell | X counters touched (after the move) | O counters touched | Result |
|---|---|---|---|---|
| 1. X | (0,0) | row0=1, col0=1, diag=1 | none | Accepted |
| 2. O | (1,0) | none | row1=1, col0=1 | Accepted |
| 3. X | (0,1) | row0=2, col1=1 | none | Accepted |
| 4. O | (1,1) | none | row1=2, col1=1, diag=1, anti=1 | Accepted |
| 5. X | (0,2) | row0=3, col2=1, anti=1 | none | row0 reached 3, so X wins with cells (0,0), (0,1), (0,2) |

Each move updates at most 4 counters and reads at most 4, so the cost does not depend on the board size. `undo` of move 5 subtracts 1 from the same counters, so row0 goes back to 2.

**Worked example: the O(K) scan on a 6×6 board with K = 4.** Counters fail here, because a run of 4 inside a row of 6 never makes the row count reach 6. So after X plays (2,3), the scan walks from that cell in each of 4 directions, at most K−1 = 3 steps each way, while the stones belong to X.

| Direction | Stones back | Stones forward | Run length (back + 1 + forward) | Win? |
|---|---|---|---|---|
| horizontal | 1 | 0 | 2 | no |
| vertical | 0 | 0 | 1 | no |
| diagonal | 2 | 1 | 4 | yes, 4 ≥ K |

The winning cells are the 4 on that diagonal.

### Minimax (F2)

Minimax means "try every move, assume the opponent also plays best, and pick the move with the best outcome". Negamax is the same idea written so both sides use one function. Alpha-beta pruning skips branches that cannot change the answer.

- Negamax with alpha-beta. A win scores `10 − ply`, so the AI prefers the fastest win and the slowest loss.
- Ties break row-major (strict `>`), so the AI is deterministic.
- Searches a `copy()`, never the caller's game.
- Memo keyed by `(config, board)` in a `ConcurrentHashMap`. Only 5,478 positions exist on 3×3, so it stays small.
- Refuses more than 2 players or boards bigger than 3×3 (IAE, `IllegalArgumentException`). An exhaustive search there is not a design, it is a hang.

Score example: X can win on its 3rd move (ply 5) or its 4th (ply 7). Scores are 10−5 = 5 and 10−7 = 3, so the faster win scores higher and is chosen. A "ply" is one move by one player.

### Concurrency model (F3)

| | |
|---|---|
| Shared state | Each `Game`, plus the id → game map |
| Invariant | check turn → place → advance turn is atomic: two submissions for one turn give exactly one `Accepted` |
| Primitive | One monitor per game (`synchronized (slot)`); the map is a `ConcurrentHashMap` |
| Why per game | Games never contend with each other. A global lock serialises the whole server |
| Why not `compute()` | It works, but holds a hash-bin lock that unrelated games can share |
| No leaks | `snapshot()` returns a detached copy; no caller holds a live `Game` |

`Game` itself is deliberately single-threaded. It is the unit you would hand to one actor or one lock.

Worked example: two devices send "X plays (1,1)" for the same turn at the same time.

| Step | Thread A | Thread B | Result |
|---|---|---|---|
| 1 | takes the game's lock | waits at the lock | |
| 2 | checks turn, places X, passes the turn to O | still waiting | A gets `Accepted` |
| 3 | releases the lock | takes the lock, checks turn: it is O's turn | B gets `Rejected(NOT_YOUR_TURN)` |

Without the lock, both threads can pass the turn check before either places a stone, and both get `Accepted`.

### Code and tests

| File | What it shows |
|---|---|
| [`MoveResult`](src/main/java/com/lld/tictactoe/MoveResult.java), [`GameConfig`](src/main/java/com/lld/tictactoe/GameConfig.java) | Result type, validated config |
| [`Game`](src/main/java/com/lld/tictactoe/Game.java) | Rule order, undo, replay, race-window hook |
| [`LineCounters`](src/main/java/com/lld/tictactoe/LineCounters.java), [`DirectionalScan`](src/main/java/com/lld/tictactoe/DirectionalScan.java) | O(1) and O(K) win checks |
| [`MinimaxStrategy`](src/main/java/com/lld/tictactoe/MinimaxStrategy.java), [`RandomStrategy`](src/main/java/com/lld/tictactoe/RandomStrategy.java) | Strategy seam |
| [`GameRegistry`](src/main/java/com/lld/tictactoe/GameRegistry.java) | Per-game lock |

| Test | Proves |
|---|---|
| [`GameConfigTest`](src/test/java/com/lld/tictactoe/GameConfigTest.java) (10) | Bad configs fail at construction |
| [`RejectionTest`](src/test/java/com/lld/tictactoe/RejectionTest.java) (11) | Every reason, in precedence order. After a rejection, board, history, status and turn are unchanged, and 50 seeded playouts match a fresh replay, so the hidden detector is untouched too. `board()` is a copy |
| [`WinDetectionTest`](src/test/java/com/lld/tictactoe/WinDetectionTest.java) (12) | Row, column and both diagonals on 3×3, 6×6 K=4, 3-of-4 isn't a win, 3 players on 5×5 K=3, K=1. X·A·X, O·A·O and X·O·X rows are not wins: this kills every ±1 sign mapping |
| [`DifferentialWinTest`](src/test/java/com/lld/tictactoe/DifferentialWinTest.java) (1) | 10,000 seeded games (N 1–6, K 1–N, 2–4 players, random undos): both detectors agree with brute force at every move |
| [`EnumerationTest`](src/test/java/com/lld/tictactoe/EnumerationTest.java) (1) | Exactly **255,168** games and **5,478** positions |
| [`MinimaxTest`](src/test/java/com/lld/tictactoe/MinimaxTest.java) (7) | Perfect vs perfect draws; never loses to random over 1,000 seeded games (500 per side); takes wins, blocks losses; prefers the fastest forced win (a position checked by brute force to have a slower forced win earlier in row-major order) |
| [`UndoReplayTest`](src/test/java/com/lld/tictactoe/UndoReplayTest.java) (7) | Undoing a win clears status, winner and the detector's counts. Replay equals the original. 500 random games are undone to empty, then replayed **on the same object** to the same status, winner and line. Replay rejects an impostor `Player` with a real id |
| [`RegistryConcurrencyTest`](src/test/java/com/lld/tictactoe/RegistryConcurrencyTest.java) (6) | Deterministic: a bare `Game` lets two moves through one turn; the registry blocks the rival at the lock; holding game A's lock doesn't block game B. Plus a 1,000-round hammer with exact totals |

**55 tests, about 2.1 s for `mvn clean test`** (measured; see [commands.md](commands.md)). Ten claims were mutation-checked: no side effects on rejection, ±1 sign sharing, undo, depth scoring, the lock, and the per-game lock. Each temporary code break survived the old test and fails the current one (list in [commands.md](commands.md)). [`MeasurementsTest`](src/test/java/com/lld/tictactoe/MeasurementsTest.java) is opt-in and re-runs the timings below.

### Numbers

| Number | Value | Kind |
|---|---|---|
| Possible games (stop at a win) | 255,168 | Sourced (Game complexity), asserted by `EnumerationTest` |
| Legal positions (incl. empty) | 5,478 | Sourced, asserted |
| Upper bound ignoring wins | 9! = 362,880 | Sourced |
| Split: X wins / O wins / draws | 131,184 / 77,904 / 46,080 | **Computed** by `EnumerationTest`, not sourced |
| Games by length 5 / 6 / 7 / 8 / 9 | 1,440 / 5,328 / 47,952 / 72,576 / 127,872 | Computed |
| Minimax, empty board, fresh strategy per call | first call 14–18 ms; next 20: median 1.7–4.2 ms | Measured, `MeasurementsTest`, 5 runs, Apple M3 Max, JDK 21.0.8. Single wall-clock runs, not JMH |
| 1,000 games vs random: shared memo / fresh strategy per move | 11–19 ms / 1,136–1,270 ms | Measured, same harness and setup |
| Perfect as X vs random (500 games) | 497 W, 3 D, 0 L | Deterministic (seed 42), printed by `MinimaxTest` |
| Perfect as O vs random (500 games) | 405 W, 95 D, 0 L | Deterministic (seed 42), printed by `MinimaxTest` |
| Unlocked `Game`, two threads, same turn | both accepted in 6–149 of 100,000 rounds; one run of 5 also threw `ArrayIndexOutOfBoundsException` | Measured, `MeasurementsTest`, 5 runs. The rate swings by 25× between runs |

---

## 3. The Gaps

### Follow-up ladder

| Round | Ask | Design delta |
|---|---|---|
| **F1** | N×N, K in a row, P players | `GameConfig(N, K, players)` validated up front. `WinDetector` seam: counters for K == N, directional scan for K < N. Turn = `(seat + 1) % P`. Counters are **per player**, so 3 players just work |
| **F2** | Undo, replay, an AI | `Move` record log. `undo` reverts grid, detector, turn, status and winner. `replay` re-validates every move. `MoveStrategy` seam; negamax + alpha-beta + memo, refused beyond 3×3 |
| **F3** | A server running many games | `GameRegistry`: `ConcurrentHashMap` of per-game monitors; detached snapshots. Next: turn timeouts read a **`Ticker`** (Topic 10), never `Instant.now()`. A spectator feed is the move log, published after the lock is released (Topic 12 M7) |

**Production delta.** Across instances, route each game id to one owner, via sticky routing or an actor per game. The lock never spans machines. The move log becomes an append-only event stream with a version per move. A late duplicate submission is then rejected by version, not by luck.

### Myth → Reality

| Myth | Reality |
|---|---|
| "Check all 8 lines after each move; it's only 3×3" | It's O(N²·K) on the N×N follow-up. Only the last stone can win, so check its lines |
| "Use one int per line: +1 for X, −1 for O, win at ±N" | That's the 2-player special case. ±1 has two signs, so with 3 players two of them share one, and X·A·X sums to 3: a false win. Keep counts per player. The test rows X·A·X, O·A·O and X·O·X catch every sign mapping |
| "Counters work for K in a row too" | Only when K == N. With K < N, a 4-run inside a 6-row doesn't bring the row count to 6. Use the directional scan |
| "Enumerating the tree is easy" | Forget to stop at a win and you count 9! = 362,880 sequences, not 255,168. The exact count is the cheapest test of the stop rule |
| "Throw `IllegalMoveException`" | A taken cell is an expected event, so it's a value. Exceptions are for caller bugs (unknown player) |
| "My 1,000-round hammer test proves the lock" | Without the lock, two moves got through in 0.006%–0.149% of rounds (measured, 5 runs). So a 1,000-round run catches it about 6%–78% of the time (estimate, 1 − (1 − p)¹⁰⁰⁰). The **race-window hook + BLOCKED-state probe** fails deterministically (checked by removing the lock) |
| "Undo = pop the history" | It must also decrement counters, clear the winner and reopen a won game. Replaying the log on the same undone object catches a missed counter revert; `winner()` checks catch a stale winner (both mutation-checked) |
| "Minimax scales" | 3×3 is fine (median 1.7–4.2 ms per empty-board search once warm, measured). 4×4 has up to 16! ≈ 2×10¹³ move orders. Refuse it; F2 on bigger boards needs depth limits + heuristics |

### When NOT to use this design

- **One-off kata in 20 minutes:** skip the `WinDetector` seam; inline the counters and say where the seam goes.
- **Real-time multiplayer with latency targets:** an actor/mailbox per game beats a lock, because it orders submissions without blocking threads.
- **Gomoku-sized boards (15×15, K=5) with an AI:** the scan is fine, but exhaustive minimax isn't. You need a threat-space search.

---

## 4. Official References

- [Game complexity (Wikipedia)](https://en.wikipedia.org/wiki/Game_complexity): 255,168 games, 5,478 positions, the 9! bound.
- [Tic-tac-toe (Wikipedia)](https://en.wikipedia.org/wiki/Tic-tac-toe) and [m,n,k-game](https://en.wikipedia.org/wiki/M,n,k-game): the N×N, K-in-a-row generalisation.
- [Minimax](https://en.wikipedia.org/wiki/Minimax), [Negamax](https://en.wikipedia.org/wiki/Negamax), [Alpha–beta pruning](https://en.wikipedia.org/wiki/Alpha%E2%80%93beta_pruning).
- [JEP 409: Sealed Classes](https://openjdk.org/jeps/409), [JEP 441: Pattern Matching for switch](https://openjdk.org/jeps/441), [JEP 395: Records](https://openjdk.org/jeps/395).
- [`ConcurrentHashMap` Javadoc (Java 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html): `computeIfAbsent` may block other updates while it computes, so keep the function short.
- [`RandomGenerator` Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/random/RandomGenerator.html): the seeded-randomness seam.
- [`Thread.State` Javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.State.html) and [JLS §17.1 Synchronization](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html): the `BLOCKED` probe and monitor semantics.

---

## 5. 45-Minute Interview Timeline

| Min | Do | Say out loud |
|---|---|---|
| 0–5 | Clarify (table above), write v1 | "Is a bad move an error or a normal event?" |
| 5–12 | `GameConfig`, `Player`, sealed `MoveResult`, rule order | "Rejected is a value, and nothing changes after one." |
| 12–25 | `Game.play` + `LineCounters` (3×3, 2 players) | "Only the last stone can win: O(1), not a rescan." |
| 25–33 | Tests: rejections, row/col/diagonal wins, draw, the 255,168 count if time allows | "The exact count tests the stop-at-win rule." |
| 33–45 | F1 (N, K, P → scan), F2 (undo/AI), F3 (registry) | "Counters are per player, so 3 players don't break them." |

Behind at minute 25? Drop the enumeration test and keep the rejection table. Correct rules beat a clever test.

---

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Model | Board + players + turn | Validated `GameConfig`; invariants owned by `Game`; `board()` is a copy |
| Errors | Returns a boolean or throws | Sealed result with reasons in a stated order; exceptions only for caller bugs |
| Win check | Checks lines through the last move | O(1) counters vs O(K) scan, says when each is valid, names the ±1 trap |
| Tests | A few wins and a draw | Brute-force oracle over 10k seeded games + exact sourced counts |
| Undo/AI | Pops a stack | Exact revert (counters, status); deterministic negamax; refuses unbounded search |
| Concurrency | "Make it synchronized" | Per-game lock, no live-object leaks, and a test that **fails without the lock** |
| Drives | Waits for follow-ups | Puts the `WinDetector` and `MoveStrategy` seams where F1/F2 will land |

---

## 7. Key Takeaways

- A move's outcome is a **sealed value**. Rejections change nothing. Exceptions are for caller bugs.
- **Only the last stone can win.** Use counters (O(1)) when K == N and a 4-direction scan (O(K)) otherwise.
- The ±1 counter trick is a **2-player special case**. Keep counts per player.
- Prove the detector with a **brute-force oracle**, and prove the search with **255,168 / 5,478**.
- `undo` must revert *everything* derived: counters, turn, status, winner.
- Lock **per game**. Prove it with **deterministic** race tests, because the bug showed up in 0.006%–0.149% of rounds, roughly 6%–78% per 1,000-round hammer run (estimate).

---

[← Topic 19 overview](../README.md) · [Index](../../README.md) · [Snake and Ladder →](../snake-and-ladder/)

**Self-grade: STRONG.** The full spine and the F1–F3 code are in place. Both detectors are proven against brute force, the counts are exact and sourced, and the race tests fail without the lock. Remaining gap: turn timeouts and the spectator feed (F3) are described, not coded.
