package com.lld.spreadsheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for F3: undo and redo restore values and dependency edges.
 */
class UndoRedoTest {

    /** Shorthand for a number value. */
    private static Value n(long v) {
        return Value.of(v);
    }

    /** Undo brings back the old text and every downstream value; redo re-applies the edit. */
    @Test
    void undoAndRedoRestoreValues() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B1", "=A1*10");
        s.set("A1", "5");
        assertEquals(n(50), s.value("B1"));
        assertTrue(s.undo());
        assertEquals("1", s.raw("A1"));
        assertEquals(n(10), s.value("B1"));
        assertTrue(s.redo());
        assertEquals(n(50), s.value("B1"));
    }

    /** Undo of the very first set empties the cell; undo and redo on empty history return false. */
    @Test
    void emptyHistory() {
        Sheet s = new Sheet();
        assertFalse(s.undo());
        assertFalse(s.redo());
        s.set("A1", "3");
        assertTrue(s.undo());
        assertEquals("", s.raw("A1"));
        assertEquals(n(0), s.value("A1"));
        assertFalse(s.undo());
    }

    /** Edit a formula, undo it, then edit a cell that the undone formula used to read: nothing fires for the stale edge. */
    @Test
    void noStaleEdgeAfterUndo() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("A2", "2");
        s.set("B1", "=A1+1");
        s.set("B1", "=A2+1");
        s.undo();
        assertEquals(Set.of(new CellId(0, 1)), s.precedentsOf("B1"));
        assertEquals(Set.of(), s.dependentsOf("A2"));
        long before = s.evaluations();
        s.set("A2", "100");
        assertEquals(1, s.evaluations() - before);
        assertEquals(n(2), s.value("B1"));
        assertTrue(s.edgesMatchFormulas());
    }

    /** Undoing a clear restores the edges of the cleared formula, and redo removes them again. */
    @Test
    void undoRestoresEdgesOfAClearedFormula() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B1", "=A1+1");
        s.set("B1", "");
        assertEquals(Set.of(), s.dependentsOf("A1"));
        s.undo();
        assertEquals(Set.of(new CellId(1, 1)), s.dependentsOf("A1"));
        s.set("A1", "7");
        assertEquals(n(8), s.value("B1"));
        s.undo();
        s.redo();
        assertTrue(s.edgesMatchFormulas());
    }

    /** A new edit throws away what could have been redone. */
    @Test
    void newEditClearsRedo() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("A1", "2");
        s.undo();
        s.set("A1", "3");
        assertFalse(s.redo());
        assertEquals("3", s.raw("A1"));
    }

    /** A rejected cyclic edit and a rejected parse are not pushed, and do not clear the redo stack. */
    @Test
    void rejectedEditsAreNotRecorded() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B1", "=A1");
        s.set("A1", "2");
        s.undo();
        assertThrows(CycleException.class, () -> s.set("A1", "=B1"));
        assertThrows(FormulaParseException.class, () -> s.set("A1", "=)"));
        assertTrue(s.redo());
        assertEquals("2", s.raw("A1"));
        s.undo();
        s.undo();
        assertEquals("", s.raw("B1"));
        assertEquals("1", s.raw("A1"));
    }

    /** Undoing a long run of edits, then redoing all of them, lands on the identical sheet. */
    @Test
    void fullUndoThenFullRedoIsIdentity() {
        Sheet s = new Sheet();
        s.set("A1", "1");
        s.set("B1", "=A1+1");
        s.set("C1", "=SUM(A1:B1)");
        s.set("A1", "=C2");
        s.set("C2", "9");
        var finalState = s.snapshot();
        while (s.undo()) {
            assertTrue(s.edgesMatchFormulas());
        }
        assertEquals(0, s.snapshot().size());
        while (s.redo()) {
            assertTrue(s.edgesMatchFormulas());
        }
        assertEquals(finalState, s.snapshot());
    }
}
