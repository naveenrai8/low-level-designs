# Topic 37: Spreadsheet, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring)

```bash
mvn -q -pl 37-spreadsheet test                                  # 44 tests (43 run, 1 benchmark skipped), about 2 s wall (measured: Apple M3 Max, JDK 21.0.8, 3 runs)
mvn -q -pl 37-spreadsheet test -Dtest=SheetTest                 # v1, cycles, evaluation counts, 10,000-cell chain
mvn -q -pl 37-spreadsheet test -Dtest=ParserTest                # grammar and error positions
mvn -q -pl 37-spreadsheet test -Dtest=UndoRedoTest              # undo, redo, no stale edges
mvn -q -pl 37-spreadsheet test -Dtest=DifferentialTest          # 3000 seeds x 40 steps against the naive oracle
python3 scripts/check_style.py 37-spreadsheet                   # style gate: expect "0 problem(s)"
```

No output from `mvn -q` means every test passed. Tests have a 10 s default timeout (`src/test/resources/junit-platform.properties`) so a mutation that loops forever fails instead of hanging.

## Benchmark (off by default)

```bash
mvn -o -pl 37-spreadsheet test -Dbench=true -Dtest=BenchmarkTest
```

Output on Apple M3 Max, JDK 21.0.8, median of 25 runs after 10 warm-up calls (microseconds):

```
N=1000 chain-head=803us chain-tail=1us fan-hub=424us fan-leaf=1us full=362us
N=5000 chain-head=3015us chain-tail=0us fan-hub=1992us fan-leaf=0us full=2388us
N=10000 chain-head=9792us chain-tail=0us fan-hub=8191us fan-leaf=0us full=8254us
```

## Mutation checks

Each mutation was applied by hand (one line) to `Sheet.java` or `Evaluator.java`, the suite was run, and the code was restored. A mutation counts as killed when the named tests fail. All were killed on the single run each; the failing set is deterministic because every collection is sorted and the random tests use fixed seeds.

| Mutation | Killed by |
|---|---|
| M1 topological order: every dirty cell starts "ready" (`if (n == 0)` becomes `if (true)`) | `diamondEvaluatesInTopologicalOrder`, `workedExample`, `DifferentialTest` and more |
| M2 old edges kept: the `unlink(old, id)` call is commented out | `oldEdgesAreRemoved`, `noStaleEdgeAfterUndo`, `DifferentialTest` |
| M3 cycle check moved after the edge swap | `rejectedCycleWritesNothing`, `selfAndRangeCycles`, `DifferentialTest` |
| M4 cycle path printed in reverse (older cycle search) | `cyclePathIsNamed`, `selfAndRangeCycles` |
| M5 dirty set widened to every cell | `chainEvaluatesExactlyTheDownstream`, `fanOutEvaluatesExactlyTheReaders`, `workedExample` |
| M6 error propagation removed from `Bin` | `errorIsNotZero`, `divisionRuleAndErrorPropagation`, `whichErrorWins`, `overflowIsAnErrorValue`, `outOfGridIsRefError` |
| M6b error treated as 0 in `SUM` (`return v` becomes `continue`) | the same five tests |
| M7 undo skips the edge rebuild | `noStaleEdgeAfterUndo`, `undoRestoresEdgesOfAClearedFormula`, `fullUndoThenFullRedoIsIdentity`, `DifferentialTest` |
| P143 `term()` right side uses `term()` instead of `unary()` (right-associative `/`) | `divisionIsLeftAssociative` |
| P157 `Neg(unary())` becomes `Neg(term())` | `unaryMinusBindsTighterThanMultiply` |
| P194 `enter()` removed from the `(` branch | `nestingDepthLimitIsEnforced` |
| E37 `Sum.cells()` row-major instead of column-major | `sumScansColumnMajor` |
| V85 `SUM` returns an overflow error instead of the cell's error | `sumErrorBeatsEarlierOverflow` |
| S259 visited check removed from the dirty-set walk | `stackedDiamondsAreWalkedOncePerEdge`, `shortestCycleIsReported` |
| S273 cycle search takes the farthest hit instead of the nearest | `shortestCycleIsReported`, `selfAndRangeCycles` |
| S341 `order.removeIf` deleted | `clearedCellIsNotInTheRecomputeOrder` |
| R dirty-set walk made recursive | `tenThousandCellChainDoesNotOverflow` (256 KB stack), plus `stackedDiamondsAreWalkedOncePerEdge`, `shortestCycleIsReported` |
