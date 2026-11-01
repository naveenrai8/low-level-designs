# Topic 19: Turn-Based Games (Tic-Tac-Toe, Snake and Ladder, Chess)

"Design a game" is the most common classic LLD prompt. (LLD means low-level design: classes, interfaces and rules, not servers.) Interviewers rarely want a game engine. They want to see you model one game's rules cleanly, keep illegal moves out, and absorb the follow-up ("now N×N", "now add undo", "now chess"). This topic ships **three standalone games**. The interesting question is what the three games would share, and why this guide doesn't extract it.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

| Game | What it really probes | Read |
|---|---|---|
| [Tic-tac-toe](tic-tac-toe/) | Incremental win check (O(1) vs O(K)), result types instead of exceptions, undo, minimax | v1 of almost every loop |
| [Snake and ladder](snake-and-ladder/) | Config validation, injected randomness, rule variants as Strategy, BFS / Markov analysis | "Design it so it's testable" |
| [Chess](chess/) | Rule-heavy legality, a representation choice, copy-make, perft as a test oracle | The hard follow-up |

Each game is its own Maven module with its own README (full problem template), `commands.md` and tests. Read them in that order. Each one is harder than the last.

The trap in the prompt "design a game engine for tic-tac-toe, snake and ladder and chess" is building the abstraction first. Three games with almost nothing in common get forced through one interface.

---

## 2. How It Solves It

### What each game keeps to itself

| Concern | Tic-tac-toe | Snake and ladder | Chess |
|---|---|---|---|
| Who chooses the move | the player | **nobody**: the dice do | the player |
| Legal-move set | empty cells | exactly one outcome per roll | position **and history** dependent (castling rights, e.p., repetition) |
| Status depends on | the board | the positions | board + side to move + move history |
| Hard part | win detection | validation and analysis | legality (pins, check, special moves) |
| Randomness | none (AI aside) | core: injected `Dice` | none |
| Undo | pop the move, revert the counters | replay from a seeded log | pop an immutable position |

### The engine you *would* extract

If a fourth and fifth game arrived and the shared parts kept getting copied, this is the seam:

```java
interface Game<S, M> {
    S state();                    // immutable snapshot
    List<M> legalMoves();         // empty for "no choice" games, or a single forced move
    MoveResult apply(M move);     // sealed result: Applied / Rejected(reason)
    Status status();              // IN_PROGRESS / WON(seat) / DRAW(reason)
}
```

Plus three pieces that really are generic:

- **seats and turn order** (including extra turns and skipped turns);
- **a move log** with undo and replay;
- **a registry** that serialises moves per game (tic-tac-toe already has one).

### Why it isn't extracted up front

| Reason | Detail |
|---|---|
| Rule of three, for real | Before you have three real users of an abstraction, its shape is a guess. Topic 05 calls this speculative generality |
| Snake and ladder has no choice | `legalMoves()` is degenerate. The dice decide, so the interface's main method means nothing for it |
| Chess status needs history | Repetition and the 50-move rule break "status is a function of the board". A shared `Status` either leaks chess concepts or loses them |
| Tic-tac-toe is too small | The engine would be larger than the game |
| The interview clock | 45 minutes spent on `Game<S, M>` is 45 minutes not spent on the rules being graded |

Staff answer: build the one game asked for, keep the seams clean (result types, an injected `Dice`/`Ticker`, an immutable state, a move log), and **name** the engine you would extract once a second game shows up.

---

## 3. The Gaps

| Myth | Reality |
|---|---|
| "An engine shows design skill" | An unused abstraction shows speculation. Clean seams inside one game show design skill |
| "Every piece is a class: `Pawn extends Piece`" | Chess legality is a function of the whole position, and promotion changes the type. The chess module uses an enum with direction tables. Its README explains why |
| "Use exceptions for illegal moves" | An illegal move is an expected outcome. Return a sealed result (Topic 04) |
| "Random dice can't be tested" | Inject the `Dice`: scripted for rule tests, seeded for statistics |
| "Undo = store the inverse of every move" | With an immutable state, undo is just popping a stack (chess). With incremental state, undo has to revert every derived counter too (tic-tac-toe) |

**When to extract the engine:** a second game is actually needed, and you've written the same turn-order and move-log code twice. Extract from the two working copies, not from a guess.

---

## 4. Official References

- Rule of three and speculative generality: Martin Fowler, *Refactoring* (2nd ed.), ch. 2 ("The Rule of Three", credited to Don Roberts) and ch. 3 ("Speculative Generality").
- Sealed classes and exhaustive `switch` (JEP 409, JEP 441): https://openjdk.org/jeps/409 · https://openjdk.org/jeps/441
- Per-game sources (perft tables, FIDE Laws, game-complexity counts, the snakes-and-ladders board) are in each game's README.

---

## 5. Key Takeaways

1. Three games, three modules, no shared engine: the rules have almost nothing in common.
2. Name the engine you'd extract (`Game<S, M>`, seats, move log, registry). Don't build it up front.
3. Illegal moves are results, not exceptions.
4. Inject the randomness and the time, so every game is deterministic in tests.
5. Pick undo by state style: an immutable snapshot means pop; incremental state means revert everything that was derived.

---

**Self-grade: STRONG.** This umbrella compares the three games, names the engine seam and gives evidence for not extracting it. Each game carries its own STRONG grade and tests (tic-tac-toe 55, snake and ladder 82, chess 142). Gap: the engine argument is reasoned, not demonstrated. No fourth game was ported onto a shared `Game<S, M>` to show where the interface strains.

[← Topic 18: Logging Framework](../18-logging-framework/) · [Index](../README.md) · [Topic 20: Library Management →](../20-library-management/)
