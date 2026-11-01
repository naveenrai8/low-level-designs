package com.lld.spreadsheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for v1 (values, graph, topological recompute), F1 (cycles) and F2 (dirty set).
 */
class SheetTest {

    /** Shorthand for a number value. */
    private static Value n(long v) {
        return Value.of(v);
    }

    /** Builds the README worked example: A1=2, A2=3, B1=A1+A2, B2=B1*2, C1=SUM(A1:B2). */
    private static Sheet example() {
        Sheet s = new Sheet();
        s.set("A1", "2");
        s.set("A2", "3");
        s.set("B1", "=A1+A2");
        s.set("B2", "=B1*2");
        s.set("C1", "=SUM(A1:B2)");
        return s;
    }

    /** The README worked example: values, and the exact recompute order after editing A1. */
    @Test
    void workedExample() {
        Sheet s = example();
        assertEquals(n(5), s.value("B1"));
        assertEquals(n(10), s.value("B2"));
        assertEquals(n(20), s.value("C1"));
        long before = s.evaluations();
        s.set("A1", "10");
        assertEquals(n(13), s.value("B1"));
        assertEquals(n(26), s.value("B2"));
        assertEquals(n(52), s.value("C1"));
        assertEquals(List.of(new CellId(0, 1), new CellId(1, 1), new CellId(1, 2), new CellId(2, 1)), s.lastRecomputeOrder());
        assertEquals(4, s.evaluations() - before);
    }

    /** A diamond (D reads B and C, both read A) evaluates D after both B and C, never before. */
    @Test
    void diamondEvaluatesInTopologicalOrder() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B1", "=A1+1");
        s.set("C1", "=A1+2");
        s.set("D1", "=B1+C1");
        s.set("A1", "10");
        assertEquals(n(23), s.value("D1"));
        List<CellId> o = s.lastRecomputeOrder();
        assertTrue(o.indexOf(new CellId(3, 1)) > o.indexOf(new CellId(1, 1)));
        assertTrue(o.indexOf(new CellId(3, 1)) > o.indexOf(new CellId(2, 1)));
        assertEquals(1, o.stream().filter(c -> c.equals(new CellId(3, 1))).count());
    }

    /** A formula that is defined before the cell it reads is computed once that cell is filled in (the edit order differs from the dependency order). */
    @Test
    void editOrderIsNotDependencyOrder() {
        Sheet s = new Sheet();
        s.set("C1", "=B1*2");
        s.set("B1", "=A1+1");
        assertEquals(n(2), s.value("C1"));
        s.set("A1", "5");
        assertEquals(n(12), s.value("C1"));
    }

    /** Division truncates toward zero, divides by zero give #DIV/0!, and the error spreads to dependents. */
    @Test
    void divisionRuleAndErrorPropagation() {
        Sheet s = new Sheet();
        s.set("A1", "=7/2");
        s.set("A2", "=-7/2");
        s.set("A3", "=1/0");
        s.set("A4", "=A3+5");
        s.set("A5", "=SUM(A1:A4)");
        s.set("A6", "=A5*0");
        assertEquals(n(3), s.value("A1"));
        assertEquals(n(-3), s.value("A2"));
        assertEquals(Value.err(ErrorKind.DIV0), s.value("A3"));
        assertEquals(Value.err(ErrorKind.DIV0), s.value("A4"));
        assertEquals(Value.err(ErrorKind.DIV0), s.value("A5"));
        assertEquals(Value.err(ErrorKind.DIV0), s.value("A6"));
        s.set("A3", "1");
        assertEquals(n(6), s.value("A4"));
        assertEquals(n(3 - 3 + 1 + 6), s.value("A5"));
        assertEquals(n(0), s.value("A6"));
    }

    /** An error is not zero: a cell that reads an error cell shows the error, while an empty cell reads as 0. */
    @Test
    void errorIsNotZero() {
        Sheet s = new Sheet();
        s.set("A1", "=1/0");
        s.set("B1", "=A1+1");
        s.set("B2", "=C9+1");
        assertEquals(Value.err(ErrorKind.DIV0), s.value("B1"));
        assertEquals(n(1), s.value("B2"));
    }

    /** The first error in a binary operation is the left one; in SUM it is the first in column-major order. */
    @Test
    void whichErrorWins() {
        Sheet s = new Sheet();
        s.set("A1", "=1/0");
        s.set("A2", "=Z100");
        s.set("B1", "=A1+A2");
        s.set("B2", "=A2+A1");
        s.set("C1", "=SUM(A1:B2)");
        assertEquals(Value.err(ErrorKind.DIV0), s.value("B1"));
        assertEquals(Value.err(ErrorKind.REF), s.value("B2"));
        assertEquals(Value.err(ErrorKind.DIV0), s.value("C1"));
    }

    /** Overflow gives #NUM! (never a wrapped number or a crash), in + - * unary minus, SUM and division of MIN_VALUE by -1. */
    @Test
    void overflowIsAnErrorValue() {
        Sheet s = new Sheet();
        s.set("A1", "9223372036854775807");
        s.set("B1", "=A1+1");
        s.set("B2", "=A1*2");
        s.set("B3", "=-A1-2");
        s.set("B4", "=SUM(A1:A2)+0");
        s.set("A2", "1");
        s.set("B5", "=(-A1-1)/-1");
        s.set("B6", "=-(-A1-1)");
        assertEquals(Value.err(ErrorKind.NUM), s.value("B1"));
        assertEquals(Value.err(ErrorKind.NUM), s.value("B2"));
        assertEquals(Value.err(ErrorKind.NUM), s.value("B3"));
        assertEquals(Value.err(ErrorKind.NUM), s.value("B4"));
        assertEquals(Value.err(ErrorKind.NUM), s.value("B5"));
        assertEquals(Value.err(ErrorKind.NUM), s.value("B6"));
        s.set("A1", "5");
        assertEquals(n(6), s.value("B1"));
    }

    /** SUM accepts corners in either order and ignores empty cells. */
    @Test
    void sumRangeCorners() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B2", "4");
        s.set("C1", "=SUM(B2:A1)");
        assertEquals(n(5), s.value("C1"));
        assertEquals(Set.of(new CellId(0, 1), new CellId(0, 2), new CellId(1, 1), new CellId(1, 2)), s.precedentsOf("C1"));
    }

    /** Replacing a formula removes the old edges in both directions, and clearing a cell removes all its edges. */
    @Test
    void oldEdgesAreRemoved() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("A2", "2");
        s.set("B1", "=A1+1");
        s.set("B1", "=A2+1");
        assertEquals(Set.of(), s.dependentsOf("A1"));
        assertEquals(Set.of(new CellId(1, 1)), s.dependentsOf("A2"));
        assertTrue(s.edgesMatchFormulas());
        long before = s.evaluations();
        s.set("A1", "100");
        assertEquals(1, s.evaluations() - before);
        assertEquals(n(3), s.value("B1"));
        s.set("B1", "");
        assertEquals(Set.of(), s.dependentsOf("A2"));
        assertEquals(n(0), s.value("B1"));
        assertTrue(s.edgesMatchFormulas());
    }

    /** Clearing a cell that others read turns it into 0 for them. */
    @Test
    void clearingAPrecedentGivesZero() {
        Sheet s = new Sheet();
        s.set("A1", "7");
        s.set("B1", "=A1+1");
        s.set("A1", "");
        assertEquals(n(1), s.value("B1"));
        assertEquals("", s.raw("A1"));
    }

    /** The cycle error names the path, each cell reading the next, ending at the start. */
    @Test
    void cyclePathIsNamed() {
        Sheet s = new Sheet();
        s.set("B1", "=C1+1");
        s.set("C1", "=A1+1");
        CycleException e = assertThrows(CycleException.class, () -> s.set("A1", "=B1"));
        assertEquals("cycle: A1 -> B1 -> C1 -> A1", e.getMessage());
    }

    /** Self reference and range-based cycles are cycles too. */
    @Test
    void selfAndRangeCycles() {
        Sheet s = new Sheet();
        assertEquals("cycle: A1 -> A1", assertThrows(CycleException.class, () -> s.set("A1", "=A1+1")).getMessage());
        assertEquals("cycle: A1 -> A1", assertThrows(CycleException.class, () -> s.set("A1", "=SUM(A1:A3)")).getMessage());
        s.set("A3", "=A2");
        s.set("A2", "5");
        assertEquals("cycle: A2 -> A2", assertThrows(CycleException.class, () -> s.set("A2", "=SUM(A1:A3)")).getMessage());
        s.set("A1", "=A3");
        assertEquals("cycle: A2 -> A1 -> A3 -> A2", assertThrows(CycleException.class, () -> s.set("A2", "=SUM(A1:A1)")).getMessage());
    }

    /** A rejected cyclic edit changes nothing: formula, value, edges, dependents' values and the order record. */
    @Test
    void rejectedCycleWritesNothing() {
        Sheet s = new Sheet();
        s.set("A1", "4");
        s.set("B1", "=A1+1");
        s.set("C1", "=B1*2");
        var snap = s.snapshot();
        long evals = s.evaluations();
        List<CellId> order = s.lastRecomputeOrder();
        assertThrows(CycleException.class, () -> s.set("A1", "=C1"));
        assertEquals("4", s.raw("A1"));
        assertEquals(snap, s.snapshot());
        assertEquals(evals, s.evaluations());
        assertEquals(order, s.lastRecomputeOrder());
        assertEquals(Set.of(), s.precedentsOf("A1"));
        assertEquals(Set.of(new CellId(1, 1)), s.dependentsOf("A1"));
        assertTrue(s.edgesMatchFormulas());
        s.set("A1", "10");
        assertEquals(n(22), s.value("C1"));
    }

    /** A bad formula also writes nothing. */
    @Test
    void rejectedParseWritesNothing() {
        Sheet s = new Sheet();
        s.set("A1", "4");
        assertThrows(FormulaParseException.class, () -> s.set("A1", "=1+"));
        assertEquals("4", s.raw("A1"));
        assertEquals(n(4), s.value("A1"));
    }

    /** Editing the head of a 1000-cell chain evaluates exactly the 1000 cells below it; editing the end evaluates one. */
    @Test
    void chainEvaluatesExactlyTheDownstream() {
        Sheet s = new Sheet(new Grid(99));
        chain(s, 1000);
        long before = s.evaluations();
        s.set("A1", "2");
        assertEquals(1000, s.evaluations() - before);
        assertEquals(n(2 + 999), s.value(name(1000)));
        before = s.evaluations();
        s.set(name(1000), "=0");
        assertEquals(1, s.evaluations() - before);
        before = s.evaluations();
        s.set(name(500), "=A1");
        assertEquals(500, s.evaluations() - before);
    }

    /** Editing the hub of a wide fan-out evaluates the hub and its readers only, and editing one reader evaluates one cell. */
    @Test
    void fanOutEvaluatesExactlyTheReaders() {
        Sheet s = new Sheet(new Grid(99));
        s.set("A1", "1");
        int readers = 0;
        for (int c = 1; c < 26; c++) {
            for (int r = 1; r <= 40; r++) {
                s.set(new CellId(c, r).toString(), "=A1+" + r);
                readers++;
            }
        }
        s.set("A50", "7");
        s.set("A51", "=A50+A50");
        long before = s.evaluations();
        s.set("A1", "2");
        assertEquals(1 + readers, s.evaluations() - before);
        before = s.evaluations();
        s.set("A50", "8");
        assertEquals(2, s.evaluations() - before);
        before = s.evaluations();
        s.set("B1", "=0");
        assertEquals(1, s.evaluations() - before);
    }

    /** A 10,000-cell chain neither overflows the stack on edit, cycle check or undo, even on a thread with a 256 KB stack where any recursion per cell would fail. */
    @Test
    void tenThousandCellChainDoesNotOverflow() throws InterruptedException {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                longChain();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", 256 * 1024);
        t.start();
        t.join();
        assertEquals(null, failure.get());
    }

    /** The body of the long-chain test: build 10,000 cells, edit the head, try a cycle through the whole chain, undo. */
    private static void longChain() {
        Sheet s = new Sheet(new Grid(400));
        for (int c = 0; c < 25; c++) {
            for (int r = 1; r <= 400; r++) {
                int i = c * 400 + r;
                s.set(new CellId(c, r).toString(), i == 1 ? "1" : "=" + prev(i) + "+1");
            }
        }
        assertEquals(n(10_000), s.value(new CellId(24, 400).toString()));
        s.set("A1", "5");
        assertEquals(n(10_004), s.value(new CellId(24, 400).toString()));
        CycleException e = assertThrows(CycleException.class, () -> s.set("A1", "=Y400"));
        assertEquals(10_001, e.path().size());
        s.undo();
        assertEquals(n(10_000), s.value(new CellId(24, 400).toString()));
    }

    /** Name of the cell before chain position i (1-based) in the 400-row layout. */
    private static String prev(int i) {
        int j = i - 1;
        return new CellId((j - 1) / 400, (j - 1) % 400 + 1).toString();
    }

    /** Name of the chain cell at position i in a one-column layout over rows 1 to 99, 2 to 26 columns. */
    private static String name(int i) {
        return new CellId((i - 1) / 99, (i - 1) % 99 + 1).toString();
    }

    /** Fills a chain of length n: the first cell is 1, each next cell is the previous plus 1. */
    private static void chain(Sheet s, int n) {
        for (int i = 1; i <= n; i++) {
            s.set(name(i), i == 1 ? "1" : "=" + name(i - 1) + "+1");
        }
    }

    /** The snapshot is a frozen copy that later edits do not change. */
    @Test
    void snapshotIsImmutableCopy() {
        Sheet s = example();
        var snap = s.snapshot();
        s.set("A1", "99");
        assertEquals(n(2), snap.get(new CellId(0, 1)));
        assertThrows(UnsupportedOperationException.class, () -> snap.put(new CellId(0, 1), n(0)));
    }

    /** Division and multiplication group left to right: 8/4/2 is (8/4)/2 = 1, not 8/(4/2) = 4. */
    @Test
    void divisionIsLeftAssociative() {
        Sheet s = new Sheet();
        s.set("A1", "=8/4/2");
        s.set("A2", "=2*3/4");
        assertEquals(n(1), s.value("A1"));
        assertEquals(n(1), s.value("A2"));
    }

    /** Unary minus binds tighter than multiplication: -A1*2 is (-A1)*2, which fits at exactly Long.MIN_VALUE, while -(A1*2) would overflow. */
    @Test
    void unaryMinusBindsTighterThanMultiply() {
        Sheet s = new Sheet();
        s.set("A1", "4611686018427387904");
        s.set("B1", "=-A1*2");
        assertEquals(n(Long.MIN_VALUE), s.value("B1"));
    }

    /** About 150 nested brackets (well under the length limit) hit the depth limit at position 101. */
    @Test
    void nestingDepthLimitIsEnforced() {
        String deep = "=" + "(".repeat(150) + "1" + ")".repeat(150);
        FormulaParseException e = assertThrows(FormulaParseException.class, () -> new Sheet().set("A1", deep));
        assertEquals(101, e.position());
        new Sheet().set("A1", "=" + "(".repeat(100) + "1" + ")".repeat(100));
    }

    /** SUM reports the first error in column-major order (A1, A2, B1, B2): the #DIV/0! in A2 beats the #REF! in B1. */
    @Test
    void sumScansColumnMajor() {
        Sheet s = new Sheet();
        s.set("B1", "=Z100");
        s.set("A2", "=1/0");
        s.set("C1", "=SUM(A1:B2)");
        assertEquals(Value.err(ErrorKind.DIV0), s.value("C1"));
    }

    /** An error cell later in a SUM still wins over an overflow that happened earlier in the same range. */
    @Test
    void sumErrorBeatsEarlierOverflow() {
        Sheet s = new Sheet();
        s.set("A1", "9223372036854775807");
        s.set("A2", "1");
        s.set("A3", "=1/0");
        s.set("B1", "=SUM(A1:A3)");
        assertEquals(Value.err(ErrorKind.DIV0), s.value("B1"));
    }

    /** With several cycles possible, the shortest one is reported: A1 = C1 closes A1 -> C1 -> A1, not the longer way through B1. */
    @Test
    void shortestCycleIsReported() {
        Sheet s = new Sheet();
        s.set("B1", "=A1");
        s.set("C1", "=A1+B1");
        assertEquals("cycle: A1 -> C1 -> A1", assertThrows(CycleException.class, () -> s.set("A1", "=C1")).getMessage());
        // Two shortest cycles tie: the one through the smaller cell name wins.
        assertEquals("cycle: A1 -> B1 -> A1", assertThrows(CycleException.class, () -> s.set("A1", "=B1+C1")).getMessage());
    }

    /** Twenty stacked diamonds have a million paths but only 80 edges; the dirty-set walk must look at each edge once. */
    @Test
    void stackedDiamondsAreWalkedOncePerEdge() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        for (int r = 1; r <= 20; r++) {
            s.set("B" + r, "=A" + r);
            s.set("C" + r, "=A" + r);
            s.set("A" + (r + 1), "=B" + r + "+C" + r);
        }
        assertEquals(n(1 << 20), s.value("A21"));
        long steps = s.graphSteps();
        s.set("A1", "2");
        assertEquals(80, s.graphSteps() - steps);
        assertEquals(n(2L << 20), s.value("A21"));
        assertEquals(61, s.lastRecomputeOrder().size());
    }

    /** Clearing a cell in the middle of a chain recomputes only the readers, and the cleared cell is not in the recompute order. */
    @Test
    void clearedCellIsNotInTheRecomputeOrder() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B1", "=A1+1");
        s.set("C1", "=B1+1");
        s.set("B1", "");
        assertEquals(List.of(new CellId(2, 1)), s.lastRecomputeOrder());
        assertEquals(n(1), s.value("C1"));
    }

    /** A snapshot taken on the writer thread can be handed to another thread and read there while the writer goes on editing. */
    @Test
    void snapshotCanBeReadOnAnotherThread() throws InterruptedException {
        Sheet s = example();
        var snap = s.snapshot();
        long[] seen = new long[1];
        Thread reader = new Thread(() -> seen[0] = snap.get(new CellId(2, 1)).number());
        s.set("A1", "1000");
        reader.start();
        reader.join();
        assertEquals(20, seen[0]);
    }
}
