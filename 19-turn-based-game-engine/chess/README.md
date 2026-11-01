# Topic 19: Chess

"Design chess" sounds like an OOP question: a `Piece` base class with a `King`, a `Queen` and so on, each one knowing its moves. That's the trap. Whether a move is legal depends on the **whole position**. A *pin* is a piece that cannot move because it shields its own king from attack. *Castling rights* are the record of whether the king and rook have ever moved. The *en-passant square* is the square a pawn just skipped over with a double push, which an enemy pawn may capture on the very next move. A promotion also changes a piece's type halfway through a game. So the real problem is **legality**, and it comes with an exact oracle: *perft*, short for "performance test". Perft counts every leaf of the move tree to a fixed depth, and the counts are published. A staff answer models pieces as data, uses one legality rule instead of ten special cases, and proves the move generator against perft. It also knows which rules (repetition, 50/75 moves) need the game's **history** rather than the board.

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

| Signal | What they want to see |
|---|---|
| Modelling | Do you reach for a class per piece, or notice that moves are data plus the position? |
| Legality | Pins and check handled by **one rule**, not a special case per situation |
| Special moves | Castling, en passant and promotion, each with its real edge case |
| State vs history | Mate needs only the board. Threefold repetition needs the move history |
| Verification | How do you *know* the move generator is right? (perft) |
| API | Untrusted text in, a value out: not exceptions for a mistyped move |

### v1 requirements (the 45-minute subset)

| | In v1 (code it) | Deferred |
|---|---|---|
| Moves | All six pieces; pawn single/double push and captures | **F1:** castling, en passant |
| Promotion | Queen only | **F1:** under-promotion to R/B/N |
| Legality | The pseudo-legal → make → "is my king attacked?" filter | – |
| Endings | Checkmate, stalemate | **F2:** insufficient material, 50/75 moves, 3/5-fold |
| Verification | Perft from the start position, d1–3 (20 / 400 / 8,902) | **F3:** the other CPW positions, divide |

v1 can be perft-verified honestly: the start position has **0** castles, e.p. captures and promotions up to depth 4 (CPW table; asserted in `startDepth4Breakdown`).

### Terms used below

| Term | Meaning |
|---|---|
| FEN | A one-line text format for a position: pieces, side to move, castling rights, en-passant square, two counters |
| UCI move | Move text such as `e2e4`: from-square, to-square, and a letter for the promotion piece (`e7e8q`) |
| Ply | One move by one side. Two plies make a full move |
| Halfmove clock | Plies since the last pawn move or capture. It drives the 50-move and 75-move rules |
| Mailbox | A board stored as an array of 64 slots, one per square |
| Copy-make | To play a move, copy the position and change the copy. The old position stays untouched |
| Dead position | A position where no series of legal moves can ever give checkmate |

### Clarifying questions (with the answers I assume)

| Question | Assumed answer |
|---|---|
| Full FIDE rules? | Yes in the end; v1 as above, the rest as follow-ups |
| Move notation? | UCI long algebraic: `e2e4`, `e1g1` for castling, `e7e8q` (lowercase). SAN is out |
| Who validates moves? | The engine. Client text is never trusted |
| Board setup? | Start position or any FEN (PGN §16.1), validated |
| Clocks, AI, networking? | Out of scope. One game is driven by one thread |

### Explicitly out of scope

SAN/PGN movetext, clocks, Chess960, search/evaluation, draw offers and resignation, the FIDE 9.2.1 and 9.3.1 claims made by announcing the move that repeats or reaches 50 moves, and dead positions that are dead because of locked pawns (only the material cases are detected).

---

## 2. How It Solves It

### Entities and invariants

| Type | What it is | Invariant |
|---|---|---|
| `Position` | Immutable value: `Piece[64]`, side to move, castling mask, e.p. square, 2 counters | One king per side; side **not** to move is not in check; no pawn on rank 1/8; a castling bit implies the king and that rook are on their home squares |
| `Move` | `record(from, to, Kind, promotion)` | Only produced by the generator; text is *matched* against it |
| `PieceType` / `Piece` | enum + `record(Color, PieceType)` (12 cached values) | – |
| `Game` | `List<Position>`, `List<Move>`, `Map<repetitionKey, count>`, `Status` | `positions = moves + 1`; the counts match the history; status is re-evaluated after every move and undo |

### Public interface first

```java
Game g = Game.standard();                  // or Game.fromFen(fen); bad FEN → IllegalArgumentException
MoveResult r = g.play("e2e4");             // Applied(move, status) | Rejected(reason)
//   reason ∈ MALFORMED | ILLEGAL | PROMOTION_PIECE_REQUIRED | GAME_OVER
g.status();          // IN_PROGRESS, CHECKMATE, STALEMATE, DRAW_INSUFFICIENT_MATERIAL,
                     // DRAW_FIVEFOLD_REPETITION, DRAW_SEVENTY_FIVE_MOVE   (automatic)
                     // DRAW_THREEFOLD_REPETITION, DRAW_FIFTY_MOVE         (only via claimDraw)
g.claimDraw();       // Claimed(status) | Rejected(GAME_OVER | NO_DRAW_TO_CLAIM)
g.winner();  g.claimableDraw();  g.undo();  g.moveLog();
Position p = g.position();                 // toFen(), legalMoves(), inCheck(), repetitionKey()
Perft.count(p, 4);  Perft.divide(p, 3);  Perft.stats(p, 3);
```

- The error contract: **user input → a value** (`Rejected`), **programmer/config input → an exception** (bad or null FEN). `play(null)` is `MALFORMED`.
- `ClaimResult` is its own sealed type, not a `MoveResult`: a claim has no `Move`, and no reason is shared.
- `undo()` takes back the last **action**; after a claim that's the claim (even with no move played). An analysis takeback: over the board, FIDE 9.5.1 forbids withdrawing a claim.

### Board representation: why a mailbox

| | **Mailbox `Piece[64]`** (this) | 0x88 (`[128]`) | Bitboards (12 × `long`) |
|---|---|---|---|
| Off-board test | file/rank bounds check | `(sq & 0x88) != 0`, one AND | masks |
| Attack test | walk rays outwards from the square | walk rays; delta-table shortcut | lookup tables + bit ops |
| Readable in 45 min | **yes** | mostly | no (magics, popcount) |
| Speed | 13.7M nodes/s, median of 5 runs, bulk-counted leaves ([commands.md](commands.md)) | not measured | not measured; the engine choice |

```
 8 | 56 57 58 59 60 61 62 63      index = rank * 8 + file      a1 = 0, h8 = 63
 7 | 48 49 50 51 52 53 54 55
 .   ...                           Never step by index: h1 + 1 = 8 = a2.
 2 |  8  9 10 11 12 13 14 15       Step by (file, rank) and bounds-check both.
 1 |  0  1  2  3  4  5  6  7
      a  b  c  d  e  f  g  h
```

### Perft by hand

Perft(depth) counts the leaf positions after exactly `depth` moves. A *leaf* is a position at the last level of the tree. White has 20 legal moves at the start (16 pawn moves, 4 knight moves). Black has 20 replies to each.

| Depth | Count | Why |
|---|---|---|
| 1 | 20 | White's 20 moves |
| 2 | 400 | 20 moves times 20 replies |
| 3 | 8,902 | Not 8,000: some moves open lines, some captures appear |

*Divide* prints the count under each root move (at depth 2: `a2a3: 20`, `a2a4: 20`, and so on). If a total is wrong, compare the divide with Stockfish's, then repeat inside the one move that differs.

### Class diagram

```
 Game ── history: List<Position>, occurrences: Map<String,Integer>, status
  │  play(uci): regex → match (from, to, promotion) in position().legalMoves()
  ▼
 Position (immutable) ── Piece[64], side, castling mask, ep, halfmove, fullmove, king squares
  │  legalMoves() = { m ∈ pseudoLegal : play(m) doesn't leave my king attacked }
  │  play(m) → NEW Position (copy-make)       repetitionKey(), hasInsufficientMaterial()
  ▼
 MoveGenerator (static) ── KNIGHT/KING steps, ORTHOGONAL/DIAGONAL rays, pawns, castling, isAttacked
 Castling (KEEP[64] mask)   Fen (parse + validate + format)   Perft (count / divide / stats)
 Move(from, to, Kind, promotion)   Piece(Color, PieceType)   MoveResult (sealed)
```

### Sequence: `play("e1g1")`

```
Game.play("e1g1")
 ├─ status over?                            → Rejected(GAME_OVER)
 ├─ regex ([a-h][1-8])([a-h][1-8])([qrbn]?) → else Rejected(MALFORMED)
 ├─ position().legalMoves()
 │    ├─ MoveGenerator.pseudoLegal: castling needs the right, rook on h1, f1+g1 empty,
 │    │                             and e1, f1, g1 not attacked
 │    └─ for each m: play(m); own king attacked? → drop
 ├─ find (e1, g1, no promotion) → CASTLE_KINGSIDE   (from/to match only promotions → PROMOTION_PIECE_REQUIRED)
 ├─ Position.play(m): king e1→g1, rook h1→f1, rights &= KEEP[e1] & KEEP[g1], ep = none, halfmove + 1
 └─ push position, occurrences[key]++, evaluate() → Applied(m, IN_PROGRESS)
```

### The legality approach

A *pseudo-legal* move is one the piece can physically make, ignoring whether it leaves its own king in check. Here is the filter on a small example. White king on e1, white knight on e2, black rook on e8, nothing else in between.

| Step | What happens | Result |
|---|---|---|
| 1 | The generator lists the knight's moves, for example `e2c3` | Pseudo-legal |
| 2 | Play `e2c3` on a copy of the position | The e-file is now open |
| 3 | Ask: is the white king on e1 attacked? The rook on e8 sees it | Yes |
| 4 | Drop `e2c3` | The knight was pinned, so it is illegal |

The same three steps (generate, play, ask "is my king attacked?") handle every other case.

| Approach | How | Cost | Where it bites |
|---|---|---|---|
| **Pseudo-legal + make + "king attacked?"** (this) | Generate geometry-only moves, play each, reject if own king is attacked | 1 copy + 1 attack scan per pseudo-legal move | Castling: the filter sees where the king **lands**, not the squares it **crossed**, so those are checked in the generator |
| Pins + check mask (engines) | Compute checkers and pinned pieces first; generate only legal moves | Fastest | Many special cases: double check, the king moving along the checking ray, the e.p. horizontal pin |

The one filter covers pins, moving into check, check evasions, and the e.p. horizontal pin (`8/8/8/KPp4r/8/8/8/7k w - c6`: `b5c6` is pseudo-legal but removes both pawns from rank 5). No pin code exists.

### How "is this square attacked?" works

`isAttacked` looks **outwards** from the square, so it needs no list of enemy moves. Example: is e1 attacked by black in the pinned-knight position above, after the knight has left?

| Step | What it checks | Result |
|---|---|---|
| 1 | Pawn squares diagonally "behind" e1 for black | No black pawn |
| 2 | The 8 knight jumps and the 8 king steps from e1 | No black knight or king |
| 3 | Walk each straight ray from e1 (up, down, left, right) until the first piece | Up the e-file: first piece is the black rook on e8 |
| 4 | Is that piece a black rook or queen? | Yes, so e1 is attacked |

Diagonal rays work the same way and look for a bishop or queen.

### State that is easy to get wrong

| Rule | Implementation | Test |
|---|---|---|
| Castling rights | 4-bit mask, `rights &= KEEP[from] & KEEP[to]`. The `to` side clears a right when its **rook is captured** on its home square | `rookCapturedOnItsHomeSquareLosesTheRight` |
| Queen-side castling | b1 must be **empty**, not safe. Only e1/d1/c1 must be unattacked (FIDE 3.8.2.2) | `queensideAllowsAttackedB1` |
| E.p. square | Set after **every** double push (PGN §16.1.3.4); the captured pawn is on `(to.file, from.rank)` | `enPassantSquareIsRecordedAfterEveryDoublePush` |
| Halfmove clock | Reset on any pawn move or capture, incl. e.p. and capture-promotion | `captureAtHalfmove149ResetsTheClock` |
| Repetition key | FEN fields 1–4, with the e.p. square only if a **legal** e.p. capture exists (FIDE 9.2.3.1) | `uncapturableEnPassant…`, `pinnedEnPassant…`, `legalEnPassant…` |
| Automatic vs claim | Automatic: mate, stalemate, dead material, 5-fold, 75 moves. Claim: 3-fold, 50 moves (FIDE 5, 9.2, 9.3, 9.6) | `GameEndTest` |
| Precedence | Mate is checked first, so mate on the 150th half-move is still mate (FIDE 9.6.2) | `mateOnTheSeventyFifthMoveIsStillMate` |
| FEN castling field | A right whose king or rook isn't home is **rejected** (IAE), like an impossible e.p. square, so round-trips are exact | `rejectsInvalidFen` (castling rows) |
| FEN e.p. field | The square and the pawn's start must be empty, a pawn must stand in front, and halfmove must be 0 | `rejectsInvalidFen` (e.p. rows) |
| Side to move | Part of the repetition key: the same board with the other side to move isn't a repetition (FIDE 9.2.3) | `sameBoardWithTheOtherSideToMoveIsNotARepetition` |

### Concurrency model

There is no shared mutable state. `Position` is immutable, so it is safe to share and cache. `Game` is **thread-confined**: one game per thread. A server would serialise moves per game id, with one actor or lock per game (as the tic-tac-toe `GameRegistry` does). Copy-make is what makes this cheap: undo is just popping a list.

### Code and tests

| File | What to look at |
|---|---|
| [`MoveGenerator`](src/main/java/com/lld/chess/MoveGenerator.java) | direction tables; `pawnMoves` (e.p., capture-promotions); `castling`; `isAttacked` looks *outwards* from the square |
| [`Position`](src/main/java/com/lld/chess/Position.java) | `play` (copy-make), `playIfLegal`, `repetitionKey`, `hasInsufficientMaterial` |
| [`Castling`](src/main/java/com/lld/chess/Castling.java) | the `KEEP[64]` mask |
| [`Fen`](src/main/java/com/lld/chess/Fen.java) | validation: kings, back-rank pawns, castling and e.p. consistency, side not to move in check |
| [`Game`](src/main/java/com/lld/chess/Game.java) | matching text against the legal list; `evaluate` precedence; claims; undo |
| [`Perft`](src/main/java/com/lld/chess/Perft.java) | `count` (bulk at depth 1), `divide`, `stats`, a Stockfish-format `main` |
| [`PerftTest`](src/test/java/com/lld/chess/PerftTest.java) | 6 CPW positions + the mirror, with capture/e.p./castle/promotion/check/mate breakdowns |
| [`DeepPerftTest`](src/test/java/com/lld/chess/DeepPerftTest.java) | `-Dperft.deep=true` only: start d6 (119,060,324), Position 3 d6, Kiwipete d4 |
| [`CastlingTest`](src/test/java/com/lld/chess/CastlingTest.java), [`EnPassantTest`](src/test/java/com/lld/chess/EnPassantTest.java), [`PromotionTest`](src/test/java/com/lld/chess/PromotionTest.java) | one test per edge case in the table above |
| [`GameEndTest`](src/test/java/com/lld/chess/GameEndTest.java), [`GameApiTest`](src/test/java/com/lld/chess/GameApiTest.java), [`FenTest`](src/test/java/com/lld/chess/FenTest.java) | draw rules, claims and undo; rejection reasons; 26 invalid-FEN cases |
| [`RandomGameTest`](src/test/java/com/lld/chess/RandomGameTest.java) | 60 seeded games: every position round-trips through FEN; undo-all returns the start |

142 tests: 140 run by default (~0.75 s summed over the test classes) and 2 deep ones skipped. Run them: see [`commands.md`](commands.md).

---

## 3. The Gaps

### Follow-up rounds

| Round | Design delta | The traps |
|---|---|---|
| **F1** "Add castling, e.p., under-promotion" | `Move.Kind` gains `DOUBLE_PUSH`, `EN_PASSANT`, `CASTLE_*`; `Position` gains a castling mask and an e.p. square; `addPawnMove` expands to 4 promotions, **for captures too** | b1 may be attacked; a rook captured on h1 loses the right; the e.p. horizontal pin; e.p. capturing a checker; a missing suffix is `PROMOTION_PIECE_REQUIRED`, not a silent queen |
| **F2** "Add draw rules and undo" | A `Game` owns the **history** (`List<Position>` + a count map), because the board can't answer "seen 3 times?". Undo = `removeLast` + decrement | The repetition key ≠ FEN; threefold/50 moves are **claims**; mate beats the 75-move rule |
| **F3** "Make it fast, prove it's right" | Prove: perft vs CPW, then `divide` vs Stockfish `go perft` and recurse into the differing move ([commands.md](commands.md)). Fast: **make/unmake**, incremental **Zobrist**, **bitboards**, pin/check-mask generation | Clocks: inject a `Ticker`, never the wall clock (Topic 10) |

### Myth → Reality

| Myth | Reality |
|---|---|
| "Sealed `Piece` classes, each with `moves()`" | Legality depends on the whole position, a promotion changes the type, and three sliders differ only in their direction set. That's a **table**, not polymorphism: an enum plus direction arrays |
| "Pins need their own code" | Play the move and ask "is my king attacked?". Pins, evasions and the e.p. horizontal pin all fall out |
| "Castling is fine if the squares between are empty and not attacked" | Only the **king's** squares must be safe. On the queen side, b1 must be empty but may be attacked |
| "Lose the right when the rook moves" | Also when it is **captured** on its home square. Perft did **not** catch this bug (table below) |
| "Perft passes, so the rules are right" | Perft counts moves. It is blind to counters, the castling field, the repetition key and draw status |
| "Threefold = the same FEN three times" | The counters always differ, and FEN records an e.p. square nobody can use. Compare placement, side, rights, and e.p. only if capturable |
| "Threefold and 50 moves end the game" | They're **claims** (FIDE 9.2, 9.3). Fivefold and 75 moves are automatic (9.6), and mate takes precedence (9.6.2) |
| "K+N+N vs K is a dead draw" | Not dead: a mate exists if the defender cooperates, and 5.2.2 says "any series of legal moves" |
| "Validate the UCI string with piece rules" | Match it against `legalMoves()`. A second validator will drift from the generator |

### What perft catches, measured

Each bug below was injected into a copy of the code, then the default suite was run. Default perft alone caught 7 of the 12. The full suite caught all 12.

| Injected bug | First perft count that's off | Unit test that catches it anyway |
|---|---|---|
| capture onto the last rank not expanded into 4 promotions | Position 4 d2: 246 ≠ 264 | `capturePromotionsAreGeneratedToo` |
| queen-only promotion | Position 4 d2: 228 ≠ 264 | `promotesToTheNamedPiece` |
| b1 required to be unattacked | Kiwipete d2: 2,038 ≠ 2,039 | `queensideAllowsAttackedB1` |
| castling out of check | **only** Position 5 d3: 62,478 ≠ 62,379 | `notOutOfCheck` |
| e.p. doesn't remove the pawn | Position 3 d2: 193 ≠ 191 | `capturesThePawnBeside…` |
| e.p. square never set | Kiwipete d2: 2,038 ≠ 2,039 | `enPassantSquareIsRecorded…` |
| pawn attack direction flipped | Position 3 d1: 15 ≠ 14 | `enPassantCanCaptureTheCheckingPawn` |
| right kept after the rook is captured on h1 | **none**, even Kiwipete d4 and Position 4/5 d4 | `rookCapturedOnItsHomeSquareLosesTheRight` |
| halfmove clock not reset on capture | none | `captureAtHalfmove149ResetsTheClock` |
| repetition key uses the raw e.p. square | none | `uncapturableEnPassant…`, `pinnedEnPassant…` |
| K+N+N vs K called dead | none | `insufficientMaterial[7,8]` |
| 75-move rule checked before mate | none | `mateOnTheSeventyFifthMoveIsStillMate` |

Why perft missed the stale castling right: the generator *also* requires the rook on its corner. That double check hides the bug from move counts, but `toFen()` and the repetition key are still wrong.

### When NOT to build it this way

| Situation | Do instead |
|---|---|
| A product | A maintained library (e.g. python-chess), not a hand-rolled rules engine |
| An engine | Search needs make/unmake, Zobrist and bitboards; copy-make + string key is the readable version |
| Chess960 | The e1/a1/h1 home-square assumptions and `e1g1` notation break (the UCI spec notes this) |

---

## 4. Official References

- FIDE Laws of Chess (from 1 Jan 2023): 3.7.3 e.p./promotion, 3.8.2 castling, 5.1–5.2 mate/stalemate/dead position, 9.2–9.3 claims, 9.2.3 "same position", 9.6 fivefold/75 moves/mate precedence: https://handbook.fide.com/chapter/E012023
- PGN Standard §16.1 FEN (16.1.3.3 castling, 16.1.3.4 e.p. target, 16.1.3.5/6 the counters): https://www.saremba.de/chessgml/standards/pgn/pgn-complete.htm#c16.1
- Chess Programming Wiki, *Perft Results* (every expected count here): https://chessprogramming.org/Perft_Results
- CPW on perft and divide: https://chessprogramming.org/Perft
- CPW on board representations: [Mailbox](https://chessprogramming.org/Mailbox) · [0x88](https://chessprogramming.org/0x88) · [Bitboards](https://chessprogramming.org/Bitboards)
- CPW on [Copy-Make](https://chessprogramming.org/Copy-Make) vs [Unmake Move](https://chessprogramming.org/Unmake_Move), [Zobrist Hashing](https://chessprogramming.org/Zobrist_Hashing), [Repetitions](https://chessprogramming.org/Repetitions)
- UCI protocol: move format `e2e4`, `e1g1`, `e7e8q`: https://backscattering.de/chess/uci/
- Stockfish `go perft` (its documented start-position d5 divide is the same 20 lines as ours once sorted): https://official-stockfish.github.io/docs/stockfish-wiki/UCI-Protocol-and-Stockfish-Commands.html
- python-chess (the "use a library" option): https://python-chess.readthedocs.io/en/latest/

---

## 5. 45-Minute Interview Timeline

| Min | Do | Say out loud |
|---|---|---|
| 0–5 | Clarify; write the v1 table; agree on UCI input | "Castling, e.p. and draws are follow-ups. I'll leave hooks for them." |
| 5–10 | `Position` (immutable), `Move` record, `PieceType` enum + direction tables, square indexing | "Pieces are data. Legality depends on the position, so no class per piece." |
| 10–25 | `steps`, `slides`, pawns, `isAttacked`, then the legality filter | "One rule: play it, and reject it if my king is attacked. That covers pins for free." |
| 25–32 | `Game.play` matching text against the legal list; mate and stalemate | "Bad input is a value, not an exception." |
| 32–40 | Tests: perft start d1–3 = 20 / 400 / 8,902, fool's mate, stalemate | "Perft is the oracle. If d3 is off, divide finds the move." |
| 40–45 | F1/F2 deltas: castling mask incl. a captured rook, e.p. square, history for repetition | "Threefold needs history, which is why `Game` exists separately from `Position`." |

If you're behind at minute 25, drop the queen-only promotion and stalemate. Keep the legality filter and one perft number. A correct generator beats extra features.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Model | Clean class per piece | Pieces as data; says why polymorphism is the wrong axis here |
| Legality | Handles check; pins via special cases | One make-and-test rule; names the castling-through-check exception |
| Special moves | Castling and e.p. work in the happy path | b1, a rook captured at home, the e.p. horizontal pin, e.p. capturing a checker, under-promotion |
| Draws | Stalemate | Claim vs automatic, the repetition key (≠ FEN), and mate beating the 75-move rule |
| Verification | Unit tests of a few moves | Perft with CPW numbers + divide against Stockfish; knows what perft can't see |
| API | `boolean move(...)`, exceptions | Untrusted text matched against the legal list; a sealed result with a reason |
| Performance | – | Copy-make vs make/unmake, Zobrist, bitboards: what each buys and when to bother |

## 7. Key Takeaways

1. Legality is a property of the **position**: pieces are data (enum + direction tables), not classes.
2. One rule: play each pseudo-legal move, drop it if your king is attacked. Castling's crossed squares are the exception.
3. Never trust move text. **Match** it against `legalMoves()`.
4. Castling rights are a mask cleared by `from` **and** `to`, which covers a rook captured at home.
5. FEN sets the e.p. square after every double push; repetition counts it only if the capture is legal.
6. Threefold and 50 moves are claims. Fivefold and 75 moves are automatic. Mate wins ties.
7. Perft + `divide` + Stockfish localise generator bugs. Perft is blind to counters and draw state (measured: 5 of 12 injected bugs passed default perft), so those need their own tests.
8. Immutable `Position` makes undo, history and sharing trivial, at 13.7M nodes/s measured here (median of 5).

---

**Self-grade: STRONG.** Full FIDE move rules perft-verified to start d6 (119,060,324), draw rules, and a measured table of what perft does and doesn't catch. Remaining gap: dead positions are material-only, the FIDE 9.2.1/9.3.1 announce-claims aren't modelled, and clocks, SAN/PGN and Chess960 are described, not coded.

[← Snake and Ladder](../snake-and-ladder/) · [Topic 19 overview](../README.md) · [Index](../../README.md) · [Topic 20: Library Management →](../../20-library-management/)
