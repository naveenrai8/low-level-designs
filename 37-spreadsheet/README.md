# Topic 37: Spreadsheet

"Build a spreadsheet." It looks like a map from `A1` to a number. The interviewer is waiting for the real problem: when `A1` changes, which other cells must change, in what order, and what happens when someone types a formula that points back at itself.

A staff answer draws the dependency graph first, recomputes only the cells downstream of the edit in topological order, rejects a cycle before touching anything and names its path, treats an error as a value that spreads, and undoes edits without leaving stale links behind.

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

Five cells. A cell holds a whole number or a formula (text starting with `=`). A formula *reads* other cells. A cell that a formula reads is its *precedent*; a cell that reads it is its *dependent*. The *dependency graph* is all those "reads" links drawn as arrows. Test: `SheetTest.workedExample`.

```
 Grid                              Graph (arrow = "is read by", pointing at the dependent)
      A      B           C
 1 |  2   | =A1+A2   | =SUM(A1:B2)         A1 --> B1 --> B2 --> C1       SUM(A1:B2) reads A1, A2, B1, B2,
 2 |  3   | =B1*2    |                     A2 --> B1,  A1 --> C1, A2 --> C1, B1 --> C1   so they all feed C1
 values: B1=5  B2=10  C1=20
```

One edit: the user types `10` into A1. The *dirty set* is the edited cell plus everything downstream of it (every cell that could now be wrong). A *topological order* is an order where every cell comes after all the cells it reads. The engine must compute in such an order, or a cell reads a stale number.

| Step | Cell computed | Why now | Result |
|---|---|---|---|
| 0 | (edit) A1 = 10 | The dirty set is A1, B1, B2, C1. A2 is not in it: nothing it reads changed. | A1 = 10 |
| 1 | A1 | It reads nothing, so it is ready. | 10 |
| 2 | B1 | Its only dirty precedent (A1) is done. | 10 + 3 = 13 |
| 3 | B2 | Its precedent B1 is done. | 13 * 2 = 26 |
| 4 | C1 | Its dirty precedents A1, B1, B2 are all done (A2 is 3, unchanged). | 10 + 3 + 13 + 26 = 52 |

Four evaluations, not five, in the order A1, B1, B2, C1. Computing C1 before B2 would read the stale B2 = 10 and give 10 + 3 + 13 + 10 = 36 instead of 52. The order the user typed the cells in (the "edit order") is not safe either: C1 may have been typed before B2.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Cell ids? | `A1` to `Z99` (26 columns, rows configurable, default 99). Upper case only. Anything else is a bad id (`IllegalArgumentException`). |
| Value type? | `long`, or an error value (`#DIV/0!`, `#REF!`, `#NUM!`). An empty cell reads as 0. |
| Formula language? | Whole numbers, cell refs, `+ - * /`, unary minus, brackets, `SUM(A1:B3)`. Spaces are allowed between tokens. A typed-in number has no spaces. A formula literal is never negative, so `=-9223372036854775808` is a parse error (the typed-in number is fine). |
| Division? | Truncates toward zero like Java: `7/2 = 3`, `-7/2 = -3`. Divide by zero is `#DIV/0!`. |
| Overflow? | Exact arithmetic (`addExact` and friends). A result that does not fit gives `#NUM!`. In `SUM`, any overflowing partial sum gives `#NUM!` (it depends on the cell order, A1 A2 B1 B2). |
| Which error wins? | An error operand beats a number. In `a op b` the left error wins. In `SUM` the first error in column-major order wins, and an error beats an overflow. |
| Reference outside the grid? | `=Z100` parses and shows `#REF!`. It is a value, not a parse error. |
| Out of scope | Strings, floats, other functions, `$A$1`, copy/paste, formatting, files, multi-sheet, REST. |

## 2. How It Solves It

### Entities and invariants

| Entity | Meaning |
|---|---|
| `CellId`, `Grid` | An address (sorted column first, then row) and the sheet size. |
| `Value`, `ErrorKind`, `Parser`, `Expr` | A number or an error (data, not an exception); hand-written recursive-descent parser and its formula tree. |
| `Evaluator`, `Edit` | Pure arithmetic on a tree; one undo Command (cell, old text, new text). |
| `Sheet` (implements `Spreadsheet`) | Cells, values, both edge maps, the undo and redo stacks. |

- I1: the edges always match the formulas. `dependents` mirrors `precedents`, and `precedents` is exactly what each stored formula reads (`edgesMatchFormulas()`).
- I2: every shown value equals what a from-scratch full recompute would give.
- I3: no cycle is ever stored.
- I4: a rejected edit writes nothing and is not put in the undo history.

### The interface and its error contract

```java
public interface Spreadsheet {
    void set(String cell, String raw);   // raw: "" clears, "42", or "=A1+SUM(B1:B3)"
    Value value(String cell);
    String raw(String cell);
    boolean undo();
    boolean redo();
    SortedMap<CellId, Value> snapshot(); // immutable copy, call it on the writer thread
}
```

| Failure | How it shows | Sheet changes? |
|---|---|---|
| Bad text (`=1+*2`) | `FormulaParseException`, message ends `at position 3` (zero-based, the `=` is position 0) | No |
| Cycle (`A1 = B1` while `B1 = A1`) | `CycleException`, message `cycle: A1 -> B1 -> A1` | No |
| Runtime problem (`=1/0`, `=Z100`, overflow) | No exception. The cell holds `#DIV/0!` etc. and so does every cell that reads it | Yes, that is the edit |

A bad cell name like `AA1` or `A0` passed to the API is an `IllegalArgumentException`.
### Class and sequence diagrams

```
 Spreadsheet <|-- Sheet --> cells: TreeMap<CellId, Cell(raw, Expr)>,  values: TreeMap<CellId, Value>
                       --> precedents (cell -> cells it reads), dependents (cell -> cells that read it)
                       --> undoStack, redoStack: Deque<Edit>       Edit(cell, oldRaw, newRaw)
 Sheet uses Parser (text -> Expr) and Evaluator (Expr + reader -> Value), both pure

 set("A1", "=B1+1")
   caller --> Sheet : CellId.parse            (bad name? IllegalArgumentException)
   Sheet --> Parser : parse                    (bad text? FormulaParseException)        } all checks
   Sheet : dirtySet(A1), then cycle check      (cycle? CycleException)                  } come before
   Sheet : ---- nothing below can fail ----                                                 } any write
   Sheet : swap edges, Kahn recompute of the dirty set, push Edit, clear redoStack
```

`set`, `undo` and `redo` all go through the same `apply(cell, raw)`.

### The algorithm and its cost

1. **Dirty set.** Breadth-first search over the forward edges (`dependents`) from the edited cell. In the example: A1 reaches B1 and C1, B1 reaches B2 and C1, B2 reaches C1. Each cell remembers the cell it was first reached from.
2. **Kahn's algorithm** (see References) over only that set. For each dirty cell count how many of its precedents are also dirty. Cells with count 0 are ready: evaluate them. When a cell is done, lower the count of its dependents; a dependent that reaches 0 becomes ready. In the example the counts are A1=0, B1=1, B2=1, C1=3, and the order is A1, B1, B2, C1.
3. **Evaluate** each ready cell once with the pure `Evaluator`. It reads other cells from the value map, which is already up to date because of the order. There is no recursion across cells, so a 10,000-cell chain cannot overflow the stack (`tenThousandCellChainDoesNotOverflow` runs it on a 256 KB stack).

Cost per edit: O(D + E_D), where D is the number of dirty cells and E_D the edges among them (a `SUM` over k cells adds k edges). A full recompute is O(all cells + all edges). Exact counts from the tests:

| Test | Edit | Cells evaluated |
|---|---|---|
| `chainEvaluatesExactlyTheDownstream`, 1000-cell chain | head, tail, or cell 500 | exactly 1000, 1, and 500 |
| `fanOutEvaluatesExactlyTheReaders`, hub read by 1000 cells | the hub, one reader, a cell read by one cell | exactly 1001, 1, and 2 |
| `stackedDiamondsAreWalkedOncePerEdge`, 20 diamonds (about a million paths) | the head | 61 evaluations, 80 edge looks |

### Cycle detection (F1): why a visited set is enough here

Before every edit the stored graph has no cycle (I3). A new formula makes cell X read the cells in `refs`. A cycle appears exactly when X can already reach one of those cells by following the "is read by" arrows, because then the new "X reads that cell" arrow closes a loop. So this is plain reachability, and the dirty set (everything reachable from X) already holds the answer: if `refs` meets the dirty set, there is a cycle. No second search is needed, and an edit with no refs skips the check.

The breadth-first walk lists cells in order of distance from X, and each remembers where it came from. The first listed cell that `refs` contains is the closest one, so following the remembered cells back gives the **shortest** cycle path. Example: `B1 = A1`, `C1 = A1+B1`, then `A1 = C1`: C1 is one step from A1 and is read by the new formula, so the path is `A1 -> C1 -> A1`, not the longer `A1 -> C1 -> B1 -> A1`. Two paths of equal length tie by cell order. A visited set (here "already in the dirty set") makes a diamond cost one visit per edge instead of one per path. Colouring cells white/grey/black is the tool for a graph that may already contain cycles (to find one from scratch). We never store one, so we do not need it.

| Edit | Dirty set of A1 (cell: reached from) | Result |
|---|---|---|
| `B1 = C1+1`, `C1 = A1+1` stored; now `A1 = B1` (refs: B1) | A1, C1 (from A1), B1 (from C1). B1 is in refs. | `cycle: A1 -> B1 -> C1 -> A1` |
| `A1 = SUM(A1:A3)` | refs contain A1, which is in the dirty set | `cycle: A1 -> A1` |

Tests: `cyclePathIsNamed`, `selfAndRangeCycles`, `shortestCycleIsReported`, `rejectedCycleWritesNothing`.

### Undo and redo (F3)

Each edit pushes an `Edit(cell, oldRaw, newRaw)` (the Command pattern, see [Topic 05](../05-patterns-that-matter/)). Undo runs `apply(cell, oldRaw)` and redo runs `apply(cell, newRaw)`.

| Step | Action | Undo stack | Redo stack | Result |
|---|---|---|---|---|
| 1 | `A1 = 1`, `B1 = =A1*10` | [A1, B1] | [] | B1 = 10 |
| 2 | `A1 = 5` | [A1, B1, A1] | [] | B1 = 50 |
| 3 | undo | [A1, B1] | [A1=5] | A1 = 1 (text back, B1 recomputed), B1 = 10 |
| 4 | redo | [A1, B1, A1] | [] | B1 = 50 |
| 5 | undo, then a new edit | [A1, B1, new] | [] | the redo entry is gone |

Because undo uses the normal edit path, it removes stale edges, adds the right ones and recomputes downstream, so values and edges come back together. A rejected edit throws before the push, so it is not recorded and does not clear redo. Undo cannot hit a cycle only because history is strictly last-in first-out: the sheet is exactly in the state the edit left it in. If you later add branching history or selective undo, the cycle check is needed again. Tests: `noStaleEdgeAfterUndo` (replace B1 `=A1+1` by `=A2+1`, undo, edit A2, expect exactly one evaluation and the right value), `undoRestoresEdgesOfAClearedFormula`, `newEditClearsRedo`, `rejectedEditsAreNotRecorded`. Which operations are legal when is [Topic 06](../06-state-machines/). Grouping keystrokes into one undo step comes in [Topic 39](../39-undo-redo-editor/).

### The concurrency model
Single writer, no locks, `Sheet` is documented as not thread-safe. An edit changes many maps in turn and I1/I2 only hold between edits, so a reader inside an edit would see a torn sheet. To share data, call `snapshot()` on the writer thread and hand the immutable copy over (volatile field, queue, thread start). I did not publish a copy after every edit: that costs O(cells) per edit and would erase the incremental win. A `ReadWriteLock` is the alternative ([Java concurrency](../../internal-pocs/Java-poc/concurrency/)); readers then wait during an edit. Test: `snapshotCanBeReadOnAnotherThread`.

### Code and tests

| File | Role |
|---|---|
| [`Spreadsheet`](src/main/java/com/lld/spreadsheet/Spreadsheet.java), [`Sheet`](src/main/java/com/lld/spreadsheet/Sheet.java) | Interface and the engine: edges, dirty set, cycle check, Kahn, undo and redo. |
| [`Parser`](src/main/java/com/lld/spreadsheet/Parser.java), [`Expr`](src/main/java/com/lld/spreadsheet/Expr.java), [`FormulaParseException`](src/main/java/com/lld/spreadsheet/FormulaParseException.java) | Recursive-descent parser, formula tree, error with position. |
| [`Evaluator`](src/main/java/com/lld/spreadsheet/Evaluator.java), [`Value`](src/main/java/com/lld/spreadsheet/Value.java), [`ErrorKind`](src/main/java/com/lld/spreadsheet/ErrorKind.java) | Pure arithmetic with exact math and error values. |
| [`CellId`](src/main/java/com/lld/spreadsheet/CellId.java), [`Grid`](src/main/java/com/lld/spreadsheet/Grid.java), [`CycleException`](src/main/java/com/lld/spreadsheet/CycleException.java), [`Edit`](src/main/java/com/lld/spreadsheet/Edit.java) | Small value types. |
| `ParserTest` (8), `SheetTest` (27), `UndoRedoTest` (7) | Grammar and positions; v1, F1, F2 with exact counts; F3. |
| `DifferentialTest` (1) + `Reference`, `BenchmarkTest` (1, off by default) | 3000 seeded runs against a naive oracle; timing with `-Dbench=true`. |

## 3. The Gaps

| Rung | Delta from the one before |
|---|---|
| F1 cycle detection | One method, `findCycle`, between "parse" and "swap edges". It reuses the dirty set and runs before any write. |
| F2 incremental recompute | Recompute only the dirty set instead of all cells; proved by evaluation counts and a differential test. |
| F3 undo and redo | `Edit`, two stacks, and routing undo and redo through `apply`. |

### F2: the differential test and the numbers

An optimisation like this is where silent bugs live (a missed edge means a stale value), so `DifferentialTest` checks the engine against `Reference`. The oracle keeps only the raw text of each cell, no graph and no dirty set. It finds cycles with its own mark-and-recurse search and evaluates every cell from scratch with memoised recursion. It does share `Parser` and `Evaluator` with the engine, so it is independent for graph and recompute logic only. Arithmetic and error precedence are guarded by the hand-written tests (`divisionRuleAndErrorPropagation`, `whichErrorWins`, `sumErrorBeatsEarlierOverflow`, `overflowIsAnErrorValue`).

3000 seeds, 40 steps each: set formulas (with `SUM`, brackets, unary minus, out-of-grid refs, huge numbers), clear, undo, redo. After every step the full value map and `edgesMatchFormulas()` must hold. Result: zero mismatches, about 1 s. The test asserts it saw more than 1000 rejected cycles and more than 1000 error cells, so a weak generator would fail.

Timing: `mvn -o -pl 37-spreadsheet test -Dbench=true -Dtest=BenchmarkTest`. Setup: Apple M3 Max, JDK 21.0.8 (OpenJDK 21.0.8.0.121), one JVM, 10 warm-up calls then the median of 25 runs. Chain: `A1=1`, each next cell `=previous+1`. Fan-out: `=A1+i`. "full" is `recomputeAll()` on the chain sheet. Microseconds:

| N cells | chain head edit (all N dirty) | chain tail edit (1 dirty) | fan-out hub edit (N dirty) | fan-out leaf edit (1 dirty) | full recompute |
|---|---|---|---|---|---|
| 1,000 | 803 | 1 | 424 | 1 | 362 |
| 5,000 | 3,015 | 0 | 1,992 | 0 | 2,388 |
| 10,000 | 9,792 | 0 | 8,191 | 0 | 8,254 |

Near the leaves an edit costs 1 us or less against 362 to 8,254 us for a full recompute, a gain of about 350x to over 8,000x. When everything is dirty, a chain-head edit costs 1.2x to 2.2x a full recompute (parsing, the dirty-set walk and in-degree maps are overhead), and a fan-out hub edit is 0.99x to 1.2x. Incremental is "do less work when you can", and when you cannot, you pay for the bookkeeping. A real engine would special-case "nearly everything is dirty". One machine, one run per cell of the table, noisy at the microsecond level (the 10,000-cell full-recompute number is 4,833 us in an earlier run, so treat single numbers as roughly right).

### Misconceptions and common mistakes

| Mistake | Why wrong | Test that catches it |
|---|---|---|
| Recompute in the order the user edited, or in map order | A cell can be computed before a cell it reads. Needs a topological order. | `diamondEvaluatesInTopologicalOrder`, `editOrderIsNotDependencyOrder` |
| Forget to remove the old edges when a formula is replaced | The old precedent still triggers recomputes, and a cell can appear to depend on something it no longer reads, giving false cycles. | `oldEdgesAreRemoved`, `noStaleEdgeAfterUndo` |
| Detect the cycle after storing the formula | The sheet is already corrupt: new formula and edges are in, the old value is gone. Validate first, then write. | `rejectedCycleWritesNothing` |
| Recursive evaluation or recursive graph walk | A long chain overflows the stack. Here the order comes from Kahn and every walk uses a queue. | `tenThousandCellChainDoesNotOverflow` (256 KB stack) |
| Treat an error as 0 | `=1/0` would silently make `SUM` look fine. An error must spread to every dependent. | `errorIsNotZero`, `whichErrorWins` |
| Unchecked `+` and `*` | Wraps around silently. Use exact methods and return `#NUM!`. | `overflowIsAnErrorValue` |
| Walk every path in a diamond | Twenty stacked diamonds have a million paths. Keep a visited set. | `stackedDiamondsAreWalkedOncePerEdge` |

### Trade-offs and when not to use

Two edge maps double the memory and the update code, but give "who do I recompute" and "what did I read" in O(1) per cell. A range `SUM(A1:Z99)` creates 2,574 edges: simple and correct, but heavy. Parsing at edit time makes recompute fast and errors early. `snapshot()` copies everything. Single writer does not suit many threads editing one sheet.

### Production and distributed delta

- **Volatile functions** like `NOW()` or `RAND()` have no precedents but must recompute every time. Keep a set of volatile cells that is always added to the dirty set.
- **The Excel calc chain.** Excel keeps an ordered list of formula cells to calculate and reorders it when a cell needs one not yet calculated ([Excel recalculation](https://learn.microsoft.com/en-us/office/client-developer/excel/excel-recalculation)). That is a cached topological order; ours rebuilds the order for the dirty set on every edit.
- **Huge sheets and lazy evaluation.** Mark cells stale and evaluate only the ones on screen or asked for.
- **Multi-user collaboration.** Edits become operations on a shared log. Two users typing `A1 = B1` and `B1 = A1` at once create a cycle neither saw, so one sequencer must validate (same cycle check) or a merge rule must exist. Operation-based editing is the subject of [Topic 39](../39-undo-redo-editor/).

## 4. Official References

- Microsoft Learn, [Excel recalculation](https://learn.microsoft.com/en-us/office/client-developer/excel/excel-recalculation) (200): smart recalculation, dependency trees, calculation chain, volatile functions.
- Wikipedia: [Topological sorting](https://en.wikipedia.org/wiki/Topological_sorting), [Kahn's algorithm](https://en.wikipedia.org/wiki/Kahn%27s_algorithm), [Depth-first search](https://en.wikipedia.org/wiki/Depth-first_search), [Dependency graph](https://en.wikipedia.org/wiki/Dependency_graph), [Recursive descent parser](https://en.wikipedia.org/wiki/Recursive_descent_parser), [Command pattern](https://en.wikipedia.org/wiki/Command_pattern) (all 200).
- Oracle, [java.lang.Math](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html) (200): `addExact`, `multiplyExact`.
- Book: Cormen et al., *Introduction to Algorithms*, 3rd edition chapter 22 (breadth-first search, depth-first search, topological sort), which is chapter 20 in the 4th edition. Not opened for this topic.

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-10 | Clarify (formula language, value type, errors, cycles, undo). Draw the 5-cell grid and graph, edit one cell, list the recompute order. |
| 10-25 | Interface and error contract (parse error vs cycle vs error value). Two edge maps, parser grammar, evaluator rules (division, overflow, which error wins). |
| 25-37 | Algorithm: dirty set, Kahn, cost. F1: validate before mutate, reachability gives the shortest path. |
| 37-43 | F3: Command with old raw text, undo through the same edit path. Concurrency: single writer plus snapshot. |
| 43-45 | Testing: exact evaluation counts, the differential test, mutation checks. Production: calc chain, volatile, lazy. |

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Recompute | Recomputes all cells, or recurses from the edited cell | Dirty set plus Kahn's algorithm, cost stated and measured, including when it loses |
| Graph | One map of dependents | Both directions kept in step, old edges removed, an invariant check in tests |
| Cycles | Detects a loop after storing it, or says "there is a cycle" | Rejects before any write, names the shortest path, reuses the dirty set, covers ranges and self-reference |
| Errors | Throws on `1/0`, uses `int` | Error values that spread, exact arithmetic, stated division and error-precedence rules, parse errors with positions |
| Testing | Examples | Exact evaluation counts, a 10,000-cell chain on a small stack, a differential test with its limits stated, mutation checks |
| Production | "Use a database" | Calc chain, volatile cells, lazy evaluation, a sequencer for collaboration |

## 7. Key Takeaways

1. A spreadsheet is a dependency graph. Recompute the edited cell's downstream set in topological order, never in edit order.
2. Keep forward and backward edges consistent. Replacing a formula must remove its old edges.
3. Validate (parse, cycle) before the first write. A rejected edit leaves everything and the undo history untouched. The cycle check is reachability from the edited cell, and the same walk gives the shortest path.
4. An error is a value that spreads to dependents. It is never 0, and it is not an exception.
5. Undo through the normal edit path so values and edges come back together. Prove an optimisation with a differential test, and know what the oracle shares with the engine.

**Self-grade: STRONG.** Full spine, v1, F1, F2 and F3 coded with 44 tests (43 run by default), a 3000-seed differential test, and mutation checks (`commands.md`). Gaps: the benchmark is one machine and shows incremental losing up to 2.2x when everything is dirty (no fast path coded); ranges cost one edge per cell; no volatile functions, lazy evaluation or collaboration; the oracle shares the parser and evaluator with the engine; the calc-chain description comes from the linked doc, not from running Excel; the CLRS chapter was not opened.

[← Topic 36: Ride/Delivery Dispatch](../36-ride-delivery-dispatch/) · [Index](../README.md) · [Topic 38: In-Memory File System →](../38-in-memory-file-system/)
