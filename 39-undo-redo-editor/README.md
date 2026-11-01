# Topic 39: Undo/Redo Editor

"Build a text editor with undo and redo." It looks like two stacks. The interviewer is waiting for the real problems: what does one Ctrl+Z undo (a keystroke or a whole word), how do you undo a delete (you must have kept the text), what happens to redo when you type something new, how much history can you keep, and how do you store a big document so that typing in the middle is not a full copy.

A staff answer uses a Command per edit that can invert itself, merges typing bursts into one step with an injected clock, validates before changing anything, caps the history, stores text in a piece table, and says where real-time collaboration changes the design.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

## 1. The Problem

### Worked example first

A *command* is one edit written down as an object, so it can be run again or run backwards. The editor keeps two stacks: *undo* (what you did) and *redo* (what you undid). Start with the text `ab`. Test: `UndoRedoTest.readmeWorkedExample` runs exactly these rows.

| Step | Action | Text | Undo stack (top on right) | Redo stack |
|---|---|---|---|---|
| 0 | start | `ab` | [] | [] |
| 1 | insert `c` at 2 | `abc` | [Insert(2,"c")] | [] |
| 2 | delete 1 char at 0 | `bc` | [Insert(2,"c"), Delete(0,"a")] | [] |
| 3 | undo | `abc` | [Insert(2,"c")] | [Delete(0,"a")] |
| 4 | redo | `bc` | [Insert(2,"c"), Delete(0,"a")] | [] |
| 5 | undo twice | `ab` | [] | [Delete(0,"a"), Insert(2,"c")] |
| 6 | insert `x` at 0 (a new edit) | `xab` | [Insert(0,"x")] | [] |

Step 6 is the rule to remember: after a new edit the old redo steps no longer make sense (they were written for a text that no longer exists), so redo is cleared. Notice that `Delete(0,"a")` keeps the text `"a"`, not just "1 character". Without the text, undo could not bring it back.

### v1 requirements (assumed answers)

- Insert text at a position, delete a range, move a cursor, undo, redo.
- Positions count Unicode code points (a code point is one Unicode number; most emoji are one, but a flag, a skin-tone emoji or a letter plus an accent is two or more). We chose this over UTF-16 chars so no edit can ever cut a surrogate pair (the two UTF-16 chars of one emoji) in half. Grapheme clusters (what a person sees as one character) are out of scope: backspace after a flag leaves half a flag, pinned by `graphemeClustersAreOutOfScope`. A production editor walks clusters with [`BreakIterator.getCharacterInstance`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/BreakIterator.html) (200).
- Undo or redo with nothing to do returns `false` (it does not throw). Picked because "nothing to undo" is a normal state, such as a button being greyed out.
- A bad position throws `IndexOutOfBoundsException`, empty text or a non-positive length throws `IllegalArgumentException`. Both before any change.

### Clarifying questions

| Question | Assumed answer |
|---|---|
| Keystroke = undo step? | No, bursts merge (F1) |
| History size? | Capped at N steps, oldest dropped |
| Large documents? | Piece table (F2) |
| Several users? | Out of v1; F3 |
| Out of scope | Selections, rich text, files, cursors of other users, multi-thread access, a byte cap on history |

## 2. How It Solves It

### Entities and invariants

- `Command` (sealed: `Insert`, `Delete`): `apply(buffer)` and `revert(buffer)`. Each stores what it needs to invert itself.
- `TextBuffer`: the text store (`PieceTable`).
- `UndoableEditor`: buffer, cursor, undo stack, redo stack, coalescing state, cap.
- I1: `undo` then `redo` gives back the same text and cursor.
- I2: a new edit empties redo.
- I3: a rejected edit changes neither text, cursor, nor either stack.
- I4: the undo stack never holds more than `maxSteps` steps.

### The interface and its error contract

```java
public interface TextEditor {
    void insert(int pos, String text);   // IndexOutOfBounds / IllegalArgument before any change
    void delete(int pos, int len);
    boolean backspace();                 // false at position 0
    void moveCursor(int pos);
    void breakCoalescing();              // close the current undo step
    boolean undo();                      // false when nothing to undo
    boolean redo();                      // false when nothing to redo
}
```

### Class diagram

```
 UndoableEditor --has--> TextBuffer <|-- PieceTable        Ot: static transform, F3
      +--> undoStack, redoStack: Deque<Command>  <|-- Insert(pos, text), Delete(pos, removedText)
```

### The algorithm and its cost

An edit goes through the same path every time: validate, build the command, decide to merge, apply, clear redo, push, trim to the cap. Validation is first, so a rejected edit has not touched anything. Undo pops a command, calls `revert`, and parks it on redo. Redo does the opposite. Undo and redo are O(cost of one apply). Memory is the size of the stored texts.

Why `revert` always works: the history is last-in first-out, so when we revert a command the text is exactly as that command left it. This is the same reason Topic 37 can undo without a cycle check.

### Coalescing (F1)

A *coalesced* step is several tiny edits stored as one command, so one Ctrl+Z removes a whole burst. The editor remembers the time of the last edit using an injected `Ticker`, so tests move a fake clock and never sleep. Pause limit here: 500 ms. Test: `CoalescingTest.timelineTable`.

| Time (ms) | Action | Merge? | Why | Steps after |
|---|---|---|---|---|
| 0 | type `a` | no | nothing to merge into | 1 |
| 100 | type `b` | yes | gap 100 ms, continues the run | 1 |
| 600 | type `c` | yes | gap 500 ms, exactly the limit still merges | 1 |
| 1101 | type `d` | no | gap 501 ms is over the limit | 2 |
| 1111 | cursor to 0, type `x` | no | the position is not the end of the last insert | 3 |
| 1121 | backspace | no | switch from insert to delete | 4 |

The full merge rules (state them out loud in the interview):

- Only single code points merge. A paste is always its own step.
- Merge only if the previous edit left the step open. Undo, redo and `breakCoalescing()` close it.
- Same kind only: insert with insert, delete with delete.
- Insert continues a run when its position is the end of the last insert. A cursor jump away breaks it. Jumping away and back to the end still merges, because the text position is what we check.
- Backspace runs merge (each delete is one position earlier). Forward deletes merge (same position).
- Deletes have no word rule. A backspace and a forward delete can merge into one step. After undoing a forward-delete run the cursor lands at the end of the restored text, not where the run began.
- Word rule for inserts (our choice): a non-space typed right after a space starts a new step, so `hi there` is two steps. Many editors do this, others do not.
- Pause rule: gap over the limit starts a new step. A gap of exactly the limit still merges.

### Piece table (F2)

A *piece table* stores the text as a list of *pieces*. Each piece says "take N characters from this spot in one of two buffers". The *original* buffer is the file as loaded and never changes. The *add* buffer only grows at its end, because all typing is appended there. The text you see is the pieces read in order. Test: `PieceTableTest.workedExample`.

| Step | Action | Pieces | Result |
|---|---|---|---|
| 0 | load `Hello world` | [orig 0..11] | `Hello world` |
| 1 | insert `,` at 5 | [orig 0..5] [add 0..1] [orig 5..11] | `Hello, world` (split one piece, nothing copied) |
| 2 | delete 4 at 5 (`, wo`) | [orig 0..5] [orig 8..11] | `Hellorld` (the add piece is dropped, the third piece is trimmed from the left) |
| 3 | type `!` at 8, then `?` at 9 | [orig 0..5] [orig 8..11] [add 1..3] | `Hellorld!?` (the add buffer already holds `,` so `!?` is add 1..3; the second key extends the same piece) |

Step 3 is the typing-burst rule: `!` gets a new piece because the piece before it is from the original buffer. `?` lands right after a piece that ends exactly at the end of the add buffer, so that piece is extended instead of a new one added. The test `workedExample` asserts the piece list after every step (`describe()`). Without it, 1000 keystrokes make 1000 pieces. Test: `typingBurstKeepsPieceCountBounded` (1000 end-inserts give 2 pieces, 1000 mid-document inserts give 3).

Cost: finding a position scans the piece list, so every operation is O(pieces). A balanced tree over the pieces makes that O(log pieces); that is what VS Code does (see references). We use a flat list: simpler, and fine while the piece count stays small. The buffers hold one `int` per code point, which costs 4 bytes per character but makes it impossible to cut a surrogate pair (the two UTF-16 chars that make one emoji).

### History cap and memory

`maxSteps` counts steps, not bytes, so one giant paste is one step and can use a lot of memory (a byte cap would need each command to report its size; not coded). When the cap is passed, the oldest step is dropped from the bottom of the undo stack. Test: `capDropsOldestSteps`.

What happens to redo: nothing. Eviction only runs when a new edit is pushed, and that edit has just emptied redo. Undone steps come back from redo onto the undo stack, so the two stacks together never exceed the cap. Test: `capAndRedoInteraction`. The add buffer does not shrink when history is dropped, and it also grows on every undo of a delete and every redo of an insert, because those re-insert by appending a copy (known limit). Production fix: keep the removed pieces inside the command and splice them back, with no copy and no growth. Not coded.

### The concurrency model

One editing thread owns the editor: no locks, no shared mutable state. Other threads (a spell checker, a saver) must not read the buffer while it changes. They get a `text()` snapshot taken by the editing thread and work on that copy. Threads and executors are in [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/).

### Collaborative editing seam (F3)

Two users edit the same text. Ann's edit reaches Bob after Bob already made his own, so applying it unchanged lands in the wrong place. *Operational transform* (OT) rewrites an operation so it means the same thing after the other operation. Test: `OtTest.workedExample`.

| Step | Ann (site 1) | Bob (site 2) | Text at each side |
|---|---|---|---|
| 0 | start `abcdef` | start `abcdef` | `abcdef` both |
| 1 | insert `X` at 0 | delete 2 chars at 2 (`cd`) | Ann `Xabcdef`, Bob `abef` |
| 2 | gets Bob's delete, shifted to pos 3 | gets Ann's insert, unchanged (0 is before 2) | Ann `Xabef`, Bob `Xabef` |

`Ot.transform(a, b)` handles insert/insert (tie on equal position goes to the lower site id), insert/delete, delete/insert (an insert landing inside a delete splits the delete in two) and delete/delete (the overlap is removed, a swallowed delete becomes nothing). A random test checks convergence for 20,000 pairs. This is only the two-operation core (it gives TP1, convergence for two sites; TP2, needed when three or more sites meet, is not covered). Policy choice: an insert that lands inside a range the other site deleted is KEPT, and the delete splits around it. A real system needs a server to order operations, version numbers, transforms across long histories, and a rule for undo: with several users, "undo" must undo only your own edits, which means transforming your old command against everyone else's later ones. The alternative is a CRDT (a data structure where every replica converges without a central order), see references. Not coded here.

### How this differs from Topic 37's undo

[Topic 37](../37-spreadsheet/) undoes one cell edit by keeping the old and new text of one cell. Here a command is an operation on a sequence (position plus text), several can merge into one step, and the operation can be moved by a transform in F3. Also, 37's inverse is state based (restore the old cell text through the normal edit path, which rebuilds the graph), while 39's is an operation inverse, which is correct only because history is last-in first-out.

### Code and tests

| File | What it holds |
|---|---|
| [`TextEditor`](src/main/java/com/lld/editor/TextEditor.java), [`UndoableEditor`](src/main/java/com/lld/editor/UndoableEditor.java) | Interface with error contract, and the stacks, coalescing and cap. |
| [`Command`](src/main/java/com/lld/editor/Command.java) | Sealed `Insert` and `Delete`, each able to revert itself. |
| [`TextBuffer`](src/main/java/com/lld/editor/TextBuffer.java), [`PieceTable`](src/main/java/com/lld/editor/PieceTable.java) | Storage interface and the piece table. |
| [`Ot`](src/main/java/com/lld/editor/Ot.java), [`Ticker`](src/main/java/com/lld/editor/Ticker.java) | The F3 transform, and the injected clock. |
| [`UndoRedoTest`](src/test/java/com/lld/editor/UndoRedoTest.java) | Identity, redo clearing, validate-before-mutate, cap, emoji. |
| [`CoalescingTest`](src/test/java/com/lld/editor/CoalescingTest.java) | The timeline table, each break rule. |
| [`PieceTableTest`](src/test/java/com/lld/editor/PieceTableTest.java), [`EditorModelTest`](src/test/java/com/lld/editor/EditorModelTest.java) | Differential tests against `StringBuilder` and against a snapshot undo model. |
| [`OtTest`](src/test/java/com/lld/editor/OtTest.java), [`BenchmarkTest`](src/test/java/com/lld/editor/BenchmarkTest.java) | OT convergence, and the opt-in timing. |

## 3. The Gaps

| Follow-up | Design delta | Where |
|---|---|---|
| F1 coalescing | Remember last edit time and kind, merge single code points, `breakCoalescing` | `UndoableEditor.merge` |
| F2 large documents | Piece table instead of a string; extend the last add piece on a typing burst | `PieceTable` |
| F3 collaboration | Transform concurrent operations (OT), or use a CRDT; undo becomes per-user | `Ot`, design only beyond that |

### F2: the differential test and the numbers

`PieceTableTest.differentialAgainstStringBuilder`: 8 seeds, 3000 random steps each (insert, delete, substring read), including position 0, the end, an empty document and ranges across pieces, with emoji snippets. After every step the text, length and piece invariants are compared with a `StringBuilder` model that uses code point offsets. `EditorModelTest`: 6 seeds, 4000 steps each, undo/redo/cap compared with a model that stores a full text snapshot per step (coalescing off). `CoalescingTest.randomCoalescingRoundTrips`: 12 seeds, 2000 steps each, with no explicit breaks, gaps of 0, 499, 500 or 501 ms, and typing, backspace, delete, cursor moves and pastes; the text matches `StringBuilder` after every step, undo-all gives the start text and redo-all the final text, so every merged step inverts exactly.

Timing (opt-in, `BenchmarkTest`): 1 MB text (1,000,000 chars), inserts of 2 chars at seeded random positions. Apple M3 Max, JDK 21.0.8, median of 7 runs after 3 warm-ups. Only the inserts are timed (`text()` is not), and the results are consumed so the work cannot be dropped.

| Inserts | Piece table (median) | `StringBuilder` (median) | Winner |
|---|---|---|---|
| 10,000 | 41.0 ms | 110.3 ms | piece table, 2.7x |
| 20,000 | 177.7 ms | 230.4 ms | piece table, 1.3x |
| 40,000 | 795.2 ms | 472.2 ms | `StringBuilder`, 1.7x |

The lead shrinks and flips. `StringBuilder.insert` is one `arraycopy` (fast memory movement) whose cost barely changes with edit count, but each flat-list insert scans every piece, and random inserts add two pieces each, so the cost per insert grows with the piece count. The crossover is between 20,000 and 40,000 random inserts into 1 MB. A balanced tree over the pieces (VS Code) removes that growth; it is not coded. Piece tables also win on huge files, undo memory and appends, which this benchmark does not measure. One machine, a toy benchmark, only random middle inserts.

### Misconceptions and common mistakes

- "Undo is a stack of snapshots." It works, but memory is the document size per step. Commands store only the change.
- "A delete command can store the length." No: undo needs the removed text.
- "One keystroke is one undo step." Users hate that. Merge bursts, and say your merge rules.
- Splitting an emoji with UTF-16 indexes. Pick one index unit and say it, and know that code points still split flags and accents (grapheme clusters).
- Validating after changing. A thrown exception halfway leaves a half-applied edit and a lying history.

### Trade-offs and when not to use

- Commands are small but each needs a correct inverse. Snapshots are trivially right and cost memory (fine for tiny state).
- Flat piece list vs tree: the list is simple and O(pieces); the benchmark above lost to `StringBuilder` at about 40,000 random inserts (tens of thousands of pieces).

### Production and distributed delta

- A tree of pieces (VS Code: red-black tree plus line-break counts), and zero-copy undo.
- Collaboration: an ordering server with OT (Google Docs style) or a CRDT (for offline-first); per-user undo.

## 4. Official References

- Gamma, Helm, Johnson, Vlissides, *Design Patterns* (GoF), chapter 5 "Command" (behavioral patterns). Book, not opened for this topic; the pattern summary is at [Wikipedia: Command pattern](https://en.wikipedia.org/wiki/Command_pattern) (200), [Piece table](https://en.wikipedia.org/wiki/Piece_table) (200).
- Charles Crowley, [Data Structures for Text Sequences](https://www.cs.unm.edu/~crowley/papers/sds.pdf) (200): compares the gap buffer, piece table and others. PDF not read in full for this topic.
- VS Code blog, [Text Buffer Reimplementation](https://code.visualstudio.com/blogs/2018/03/23/text-buffer-reimplementation) (200): the piece tree, and why they moved from a line array.
- [Wikipedia: Operational transformation](https://en.wikipedia.org/wiki/Operational_transformation) (200). The original paper (Ellis and Gibbs, SIGMOD 1989, doi 10.1145/67544.66963) returns 403 to curl, so its details are not checked here. Our transform was tested by property, not copied from the paper.
- [Wikipedia: CRDT](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type) (200) and [crdt.tech](https://crdt.tech/) (200).
- Related guides: [Topic 37](../37-spreadsheet/) (undo of a cell), [Topic 05](../05-patterns-that-matter/) (Command and other patterns), [Topic 10](../10-time-and-testability/) (injected time).

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-8 | Clarify (index unit, redo rule, cap, size, users). Draw the worked example table. |
| 8-20 | Interface, error contract, Command with stored inverse, the two stacks, validate-before-mutate. |
| 20-30 | F1: the merge rules and the timeline table, injected clock. |
| 30-38 | F2: piece table, why it beats copying, the typing-burst rule. |
| 38-43 | History cap and what happens to redo. F3: OT in one example, per-user undo. |
| 43-45 | Tests: differential, timeline, mutation checks. |

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Undo model | Two stacks of commands | Plus inverse stored in the command (deleted text), redo cleared on new edit, validate before mutate |
| Typing | One step per key | Merge rules stated with their breaks, injected clock, tested with a timeline |
| Storage | String concatenation | Piece table with a stated cost and a differential test |
| Memory | Unbounded history | Cap, and what it means for redo |
| Characters | "Strings" | Code points vs UTF-16 chosen and tested with an emoji, and grapheme clusters named as out of scope |
| Collaboration | "Use a lock" | OT or CRDT, per-user undo, what a sequencer does |

## 7. Key Takeaways

1. A command stores its own inverse, so a delete stores the deleted text.
2. A new edit clears redo, and a rejected edit changes nothing, not even the stacks.
3. Merge typing bursts with explicit rules (kind, position, pause, word, break) and an injected clock.
4. A piece table never rewrites text. Extending the last add piece keeps the piece count flat during typing.
5. Cap the history by steps and state that eviction cannot touch redo. Collaboration turns undo into a transform problem.

**Self-grade: STRONG.** Full spine, v1, F1, F2 and the F3 transform coded with 47 tests (46 run by default), three differential tests (piece table, snapshot undo model, random coalescing), and mutation checks (`commands.md`). Gaps: flat piece list (O(pieces)), no byte cap, the add buffer never shrinks, the benchmark is one machine and random middle inserts only, with the flat list losing past about 30,000 inserts, OT is the two-operation core only (no server, no per-user undo), and the GoF chapter and Crowley paper were cited, not read for this topic.

[← Topic 38: In-Memory File System](../38-in-memory-file-system/) · [Index](../README.md) · [Topic 40: Autocomplete Top-K →](../40-autocomplete-top-k/)
