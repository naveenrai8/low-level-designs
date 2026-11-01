package com.lld.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests of when keystrokes merge into one undo step and when they must not. */
class CoalescingTest {

    private FakeTicker ticker;
    private UndoableEditor e;

    /** Creates an editor with an empty text and a 500 ms pause limit. */
    @BeforeEach
    void setUp() {
        ticker = new FakeTicker();
        e = UndoableEditor.of("", ticker, Duration.ofMillis(500), 100);
    }

    /** Types each character of {@code s} with 100 ms between keys. */
    private void typeFast(String s) {
        s.codePoints().forEach(cp -> {
            e.type(new String(Character.toChars(cp)));
            ticker.advanceMillis(100);
        });
    }

    /** Proves a burst of single-character inserts is one undo step and one undo removes it all. */
    @Test
    void typingBurstIsOneStep() {
        typeFast("hello");
        assertEquals(1, e.undoDepth());
        e.undo();
        assertEquals("", e.text());
    }

    /** Proves the timeline table: each row is a keystroke and the step count after it. */
    @Test
    void timelineTable() {
        e.type("a");
        assertEquals(1, e.undoDepth());
        ticker.advanceMillis(100);
        e.type("b");
        assertEquals(1, e.undoDepth());
        ticker.advanceMillis(500);
        e.type("c");
        assertEquals(1, e.undoDepth());
        ticker.advanceMillis(501);
        e.type("d");
        assertEquals(2, e.undoDepth());
        e.moveCursor(0);
        ticker.advanceMillis(10);
        e.type("x");
        assertEquals(3, e.undoDepth());
        ticker.advanceMillis(10);
        e.backspace();
        assertEquals(4, e.undoDepth());
        assertEquals("abcd", e.text());
    }

    /** Proves a pause longer than the limit starts a new step, and exactly the limit still merges. */
    @Test
    void pauseBreaksButBoundaryMerges() {
        e.type("a");
        ticker.advanceMillis(500);
        e.type("b");
        assertEquals(1, e.undoDepth());
        ticker.advanceMillis(501);
        e.type("c");
        assertEquals(2, e.undoDepth());
    }

    /** Proves moving the cursor elsewhere and typing starts a new step. */
    @Test
    void cursorJumpBreaks() {
        typeFast("abc");
        e.moveCursor(0);
        e.type("x");
        assertEquals(2, e.undoDepth());
        assertEquals("xabc", e.text());
    }

    /** Proves moving the cursor away and back to the run's end still merges, because the position continues the run. */
    @Test
    void cursorBackToRunEndMerges() {
        typeFast("ab");
        e.moveCursor(0);
        e.moveCursor(2);
        e.type("c");
        assertEquals(1, e.undoDepth());
    }

    /** Proves switching between typing and deleting breaks the step in both directions. */
    @Test
    void switchBetweenInsertAndDeleteBreaks() {
        typeFast("abc");
        e.backspace();
        assertEquals(2, e.undoDepth());
        e.type("z");
        assertEquals(3, e.undoDepth());
    }

    /** Proves a run of backspaces is one step and undo restores all the deleted text in order. */
    @Test
    void backspaceRunCoalesces() {
        e = UndoableEditor.of("hello", ticker, Duration.ofMillis(500), 100);
        e.backspace();
        e.backspace();
        e.backspace();
        assertEquals("he", e.text());
        assertEquals(1, e.undoDepth());
        e.undo();
        assertEquals("hello", e.text());
        e.redo();
        assertEquals("he", e.text());
    }

    /** Proves forward deletes at the same position coalesce too. */
    @Test
    void forwardDeleteRunCoalesces() {
        e = UndoableEditor.of("hello", ticker, Duration.ofMillis(500), 100);
        e.delete(1, 1);
        e.delete(1, 1);
        assertEquals(1, e.undoDepth());
        assertEquals("hlo", e.text());
        e.undo();
        assertEquals("hello", e.text());
    }

    /** Proves an explicit break splits an otherwise mergeable run. */
    @Test
    void explicitBreak() {
        e.type("a");
        e.breakCoalescing();
        e.type("b");
        assertEquals(2, e.undoDepth());
    }

    /** Proves undo closes the step: typing after undo does not merge into the step just undone, and redo is cleared. */
    @Test
    void undoClosesTheStep() {
        typeFast("ab");
        e.undo();
        e.type("c");
        assertEquals(1, e.undoDepth());
        assertFalse(e.redo());
        typeFast("d");
        assertEquals("cd", e.text());
        assertEquals(1, e.undoDepth());
    }

    /** Proves the word rule: a letter typed after whitespace starts a new step, so each word is a step. */
    @Test
    void wordBoundaryBreaks() {
        typeFast("hi there");
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("hi ", e.text());
    }

    /** Proves multi-character inserts (a paste) are never merged. */
    @Test
    void pasteIsItsOwnStep() {
        e.type("a");
        e.type("bc");
        e.type("d");
        assertEquals(3, e.undoDepth());
    }

    /** Proves typing emoji one by one merges and undo removes them whole. */
    @Test
    void emojiBurst() {
        typeFast("😀😁");
        assertEquals(2, e.cursor());
        assertEquals(1, e.undoDepth());
        e.undo();
        assertEquals("", e.text());
    }

    /** Proves an editing session round trip: undo everything then redo everything gives the same text. */
    @Test
    void fullRoundTrip() {
        typeFast("one two three");
        e.backspace();
        String end = e.text();
        while (e.undo()) {
            // undo all
        }
        assertEquals("", e.text());
        while (e.redo()) {
            // redo all
        }
        assertEquals(end, e.text());
    }

    /** Proves undo closes the step even when an older step is underneath: typing after an undo is a new step, not part of the older one. */
    @Test
    void undoDoesNotReopenTheStepBelow() {
        e.type("a");
        e.breakCoalescing();
        e.type("b");
        e.undo();
        e.type("c");
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("a", e.text());
    }

    /** Proves a delete that starts exactly where the last insert ended is a different kind of edit and stays its own step. */
    @Test
    void deleteRightAfterInsertEndStaysSeparate() {
        e = UndoableEditor.of("xyz", ticker, Duration.ofMillis(500), 100);
        e.insert(0, "a");
        e.delete(1, 1);
        assertEquals(2, e.undoDepth());
        assertEquals("ayz", e.text());
        e.undo();
        assertEquals("axyz", e.text());
    }

    /** Proves a delete elsewhere does not join a delete run (S3). */
    @Test
    void deleteElsewhereBreaksDeleteRun() {
        e = UndoableEditor.of("hello", ticker, Duration.ofMillis(500), 100);
        e.delete(0, 1);
        e.delete(3, 1);
        assertEquals(2, e.undoDepth());
    }

    /** Proves a letter after two spaces still starts a new step: the rule looks at the last character typed (S4). */
    @Test
    void wordRuleLooksAtLastCharOnly() {
        typeFast("a  b");
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("a  ", e.text());
    }

    /** Proves the pause rule uses elapsed time, so it still works when the nanosecond clock wraps past Long.MAX_VALUE (S5). */
    @Test
    void pauseRuleSurvivesClockWrap() {
        FakeTicker near = new FakeTicker(Long.MAX_VALUE - 100_000_000L);
        UndoableEditor w = UndoableEditor.of("", near, Duration.ofMillis(500), 100);
        w.type("a");
        near.advanceMillis(50);
        w.type("b");
        assertEquals(1, w.undoDepth());
    }

    /** Proves a rejected edit leaves the coalescing state alone: it neither restarts the pause clock (400 + 400 ms is still too long) nor closes the open step (400 + 50 ms still merges). */
    @Test
    void rejectedEditDoesNotTouchCoalescingState() {
        e.type("a");
        ticker.advanceMillis(400);
        assertThrows(IndexOutOfBoundsException.class, () -> e.insert(99, "x"));
        ticker.advanceMillis(400);
        e.type("b");
        assertEquals(2, e.undoDepth());
        UndoableEditor f = UndoableEditor.of("", ticker, Duration.ofMillis(500), 100);
        f.type("a");
        ticker.advanceMillis(400);
        assertThrows(IndexOutOfBoundsException.class, () -> f.insert(99, "x"));
        ticker.advanceMillis(50);
        f.type("b");
        assertEquals(1, f.undoDepth());
    }

    /** Proves a backspace and a forward delete can merge into one step, and undo restores the text in order. */
    @Test
    void backspaceThenForwardDeleteMerge() {
        e = UndoableEditor.of("abcd", ticker, Duration.ofMillis(500), 100);
        e.moveCursor(2);
        e.backspace();
        e.delete(1, 1);
        assertEquals("ad", e.text());
        assertEquals(1, e.undoDepth());
        e.undo();
        assertEquals("abcd", e.text());
        e.redo();
        assertEquals("ad", e.text());
    }

    /** Runs random typing, deleting, cursor moves and pastes with no explicit breaks and pauses of 0, 499, 500 or 501 ms. The text must match StringBuilder after every op, undo-all must give the start text and redo-all the final text. */
    @Test
    void randomCoalescingRoundTrips() {
        int[] gaps = {0, 499, 500, 501};
        String[] pastes = {"xy", "😀z", "a b"};
        for (long seed = 1; seed <= 12; seed++) {
            Random r = new Random(seed);
            FakeTicker tk = new FakeTicker();
            String start = "start text";
            UndoableEditor ed = UndoableEditor.of(start, tk, Duration.ofMillis(500), 100_000);
            StringBuilder model = new StringBuilder(start);
            int cur = model.length();
            for (int i = 0; i < 2000; i++) {
                tk.advanceMillis(gaps[r.nextInt(gaps.length)]);
                int len = model.codePointCount(0, model.length());
                int op = r.nextInt(6);
                if (op <= 1) {
                    String ch = op == 0 ? "a" : " ";
                    model.insert(model.offsetByCodePoints(0, cur), ch);
                    ed.type(ch);
                    cur++;
                } else if (op == 2 && cur > 0) {
                    int from = model.offsetByCodePoints(0, cur - 1);
                    model.delete(from, model.offsetByCodePoints(from, 1));
                    assertTrue(ed.backspace());
                    cur--;
                } else if (op == 3 && len > 0) {
                    int pos = r.nextInt(len);
                    int from = model.offsetByCodePoints(0, pos);
                    model.delete(from, model.offsetByCodePoints(from, 1));
                    ed.delete(pos, 1);
                    cur = pos;
                } else if (op == 4) {
                    cur = r.nextInt(len + 1);
                    ed.moveCursor(cur);
                } else if (op == 5) {
                    String s = pastes[r.nextInt(pastes.length)];
                    model.insert(model.offsetByCodePoints(0, cur), s);
                    ed.type(s);
                    cur += s.codePointCount(0, s.length());
                }
                assertEquals(model.toString(), ed.text(), "seed " + seed + " step " + i);
                assertEquals(cur, ed.cursor());
            }
            String end = ed.text();
            int steps = ed.undoDepth();
            assertTrue(steps < 1500, "coalescing should merge some steps");
            while (ed.undo()) {
                // undo all
            }
            assertEquals(start, ed.text(), "seed " + seed);
            while (ed.redo()) {
                // redo all
            }
            assertEquals(end, ed.text(), "seed " + seed);
        }
    }
}
