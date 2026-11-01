package com.lld.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests of the Command pattern core: stacks, inversion, validation and the history cap. */
class UndoRedoTest {

    private FakeTicker ticker;

    /** Creates a fresh fake clock for each test. */
    @BeforeEach
    void setUp() {
        ticker = new FakeTicker();
    }

    /** Makes an editor over {@code initial} with a 1 s pause limit and the given cap. */
    private UndoableEditor editor(String initial, int cap) {
        return UndoableEditor.of(initial, ticker, Duration.ofSeconds(1), cap);
    }

    /** Proves undo then redo returns to exactly the same text and cursor, for inserts and deletes. */
    @Test
    void undoThenRedoIsIdentity() {
        UndoableEditor e = editor("hello world", 10);
        e.insert(5, ",");
        e.breakCoalescing();
        e.delete(0, 2);
        String afterBoth = e.text();
        int cur = e.cursor();
        assertTrue(e.undo());
        assertTrue(e.undo());
        assertEquals("hello world", e.text());
        assertTrue(e.redo());
        assertTrue(e.redo());
        assertEquals(afterBoth, e.text());
        assertEquals(cur, e.cursor());
    }

    /** Proves a new edit after an undo clears the redo stack. */
    @Test
    void newEditClearsRedo() {
        UndoableEditor e = editor("ab", 10);
        e.insert(2, "c");
        e.undo();
        assertEquals(1, e.redoDepth());
        e.insert(0, "x");
        assertEquals(0, e.redoDepth());
        assertFalse(e.redo());
        assertEquals("xab", e.text());
    }

    /** Proves undo and redo on empty stacks return false, change nothing and never throw. */
    @Test
    void emptyStacksReturnFalse() {
        UndoableEditor e = editor("abc", 10);
        assertFalse(e.undo());
        assertFalse(e.redo());
        assertEquals("abc", e.text());
    }

    /** Proves a delete stores the removed text, so undo restores the exact characters. */
    @Test
    void deleteStoresTheDeletedText() {
        PieceTable t = new PieceTable("0123456789");
        Command d = new Command.Delete(3, t.substring(3, 6));
        assertEquals("345", d.text());
        d.apply(t);
        assertEquals("0126789", t.text());
        d.revert(t);
        assertEquals("0123456789", t.text());
    }

    /** Proves every rejected edit leaves text, cursor and both stacks untouched. */
    @Test
    void rejectedEditChangesNothing() {
        UndoableEditor e = editor("abc", 10);
        e.insert(3, "d");
        e.undo();
        e.moveCursor(1);
        int undo = e.undoDepth();
        int redo = e.redoDepth();
        assertThrows(IndexOutOfBoundsException.class, () -> e.insert(9, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> e.insert(-1, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> e.delete(2, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> e.delete(9, 1));
        assertThrows(IllegalArgumentException.class, () -> e.insert(0, ""));
        assertThrows(IllegalArgumentException.class, () -> e.delete(0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> e.moveCursor(4));
        assertEquals("abc", e.text());
        assertEquals(1, e.cursor());
        assertEquals(undo, e.undoDepth());
        assertEquals(redo, e.redoDepth());
    }

    /** Proves the cap drops the oldest steps first, so undoing past the cap stops there. */
    @Test
    void capDropsOldestSteps() {
        UndoableEditor e = editor("", 3);
        for (String s : new String[] {"a", "b", "c", "d", "e"}) {
            e.insert(e.cursor(), s);
            e.breakCoalescing();
        }
        assertEquals(3, e.undoDepth());
        while (e.undo()) {
            // keep undoing
        }
        assertEquals("ab", e.text());
    }

    /** Proves eviction never touches redo: redo was cleared by the edit that caused the eviction, and undone steps return to the capped stack. */
    @Test
    void capAndRedoInteraction() {
        UndoableEditor e = editor("", 2);
        for (String s : new String[] {"a", "b", "c"}) {
            e.insert(e.cursor(), s);
            e.breakCoalescing();
        }
        assertTrue(e.undo());
        assertTrue(e.undo());
        assertEquals(2, e.redoDepth());
        assertTrue(e.redo());
        assertTrue(e.redo());
        assertEquals("abc", e.text());
        assertEquals(2, e.undoDepth());
        assertEquals(0, e.redoDepth());
    }

    /** Proves the constructor rejects a cap below 1. */
    @Test
    void capMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> editor("", 0));
    }

    /** Proves an emoji is one position: backspace removes the whole emoji and undo brings it back whole. */
    @Test
    void emojiIsOnePosition() {
        UndoableEditor e = editor("a😀b", 10);
        assertEquals(3, e.cursor());
        e.moveCursor(2);
        assertTrue(e.backspace());
        assertEquals("ab", e.text());
        e.undo();
        assertEquals("a😀b", e.text());
        assertThrows(IndexOutOfBoundsException.class, () -> e.insert(4, "x"));
    }

    /** Proves undoing a backspace puts the cursor back at the end of the restored text (S1). */
    @Test
    void undoOfBackspaceRestoresCursor() {
        UndoableEditor e = editor("hello", 10);
        e.backspace();
        assertEquals(4, e.cursor());
        e.undo();
        assertEquals("hello", e.text());
        assertEquals(5, e.cursor());
    }

    /** Proves redo of an insert leaves the cursor after the inserted text, so the next key continues there (S2). */
    @Test
    void redoOfInsertMovesCursorToEnd() {
        UndoableEditor e = editor("", 10);
        e.type("ab");
        e.undo();
        e.redo();
        assertEquals(2, e.cursor());
        e.type("c");
        assertEquals("abc", e.text());
    }

    /** Pins a known limit: positions are code points, not grapheme clusters, so backspace after a flag (two code points) leaves half a flag. */
    @Test
    void graphemeClustersAreOutOfScope() {
        UndoableEditor e = editor("\uD83C\uDDFA\uD83C\uDDF8", 10);
        assertEquals(2, e.cursor());
        e.backspace();
        assertEquals("\uD83C\uDDFA", e.text());
        e.undo();
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8", e.text());
    }

    /** Runs the README worked example row by row: text, undo depth and redo depth after each of the six steps. */
    @Test
    void readmeWorkedExample() {
        UndoableEditor e = editor("ab", 10);
        e.insert(2, "c");
        assertEquals("abc", e.text());
        assertEquals(1, e.undoDepth());
        e.breakCoalescing();
        e.delete(0, 1);
        assertEquals("bc", e.text());
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("abc", e.text());
        assertEquals(1, e.redoDepth());
        e.redo();
        assertEquals("bc", e.text());
        assertEquals(0, e.redoDepth());
        e.undo();
        e.undo();
        assertEquals("ab", e.text());
        assertEquals(0, e.undoDepth());
        assertEquals(2, e.redoDepth());
        e.insert(0, "x");
        assertEquals("xab", e.text());
        assertEquals(1, e.undoDepth());
        assertEquals(0, e.redoDepth());
    }
}
