package com.lld.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests of the piece table: examples, piece count, code points and a differential test against a naive model. */
class PieceTableTest {

    /** Proves the worked example from the README row by row: the exact piece list after each step. */
    @Test
    void workedExample() {
        PieceTable t = new PieceTable("Hello world");
        assertEquals("[o0..11]", t.describe());
        t.insert(5, ",");
        assertEquals("Hello, world", t.text());
        assertEquals("[o0..5][a0..1][o5..11]", t.describe());
        assertEquals(", wo", t.delete(5, 4));
        assertEquals("Hellorld", t.text());
        assertEquals("[o0..5][o8..11]", t.describe());
        t.insert(8, "!");
        t.insert(9, "?");
        assertEquals("Hellorld!?", t.text());
        assertEquals("[o0..5][o8..11][a1..3]", t.describe());
        assertTrue(t.invariantsHold());
    }

    /** Proves a typing burst at the end stays one extra piece instead of one piece per key. */
    @Test
    void typingBurstKeepsPieceCountBounded() {
        PieceTable t = new PieceTable("start");
        for (int i = 0; i < 1000; i++) {
            t.insert(t.length(), "x");
        }
        assertEquals(2, t.pieceCount());
        PieceTable mid = new PieceTable("start");
        mid.insert(2, "a");
        for (int i = 1; i < 1000; i++) {
            mid.insert(2 + i, "a");
        }
        assertEquals(3, mid.pieceCount());
        assertEquals(1005, mid.length());
    }

    /** Proves an empty document works: insert, delete everything, insert again. */
    @Test
    void emptyDocument() {
        PieceTable t = new PieceTable("");
        assertEquals(0, t.pieceCount());
        t.insert(0, "ab");
        assertEquals("ab", t.delete(0, 2));
        assertEquals(0, t.pieceCount());
        t.insert(0, "z");
        assertEquals("z", t.text());
    }

    /** Proves bad positions throw and leave the text alone. */
    @Test
    void badPositionsThrow() {
        PieceTable t = new PieceTable("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> t.insert(4, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> t.insert(-1, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> t.delete(2, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> t.delete(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> t.substring(1, 5));
        assertEquals("abc", t.text());
    }

    /** Proves positions count code points: an emoji is one, and it is never split by an insert or delete. */
    @Test
    void codePointIndexing() {
        PieceTable t = new PieceTable("a😀b");
        assertEquals(3, t.length());
        t.insert(2, "-");
        assertEquals("a😀-b", t.text());
        assertEquals("😀", t.delete(1, 1));
        assertEquals("a-b", t.text());
        t.insert(1, "😁😂");
        assertEquals(5, t.length());
        assertEquals("😂", t.substring(2, 3));
    }

    /** Compares the piece table with a StringBuilder (by code points) after every operation, for several seeds. */
    @Test
    void differentialAgainstStringBuilder() {
        String[] snippets = {"a", "xy", "😀", "hello world", "", "😁b😂"};
        for (long seed = 1; seed <= 8; seed++) {
            Random r = new Random(seed);
            String start = seed % 2 == 0 ? "" : "The quick brown fox";
            StringBuilder model = new StringBuilder(start);
            PieceTable t = new PieceTable(start);
            for (int i = 0; i < 3000; i++) {
                int len = model.codePointCount(0, model.length());
                int op = r.nextInt(3);
                if (op == 0) {
                    int pos = pick(r, len);
                    String s = snippets[r.nextInt(snippets.length)];
                    model.insert(model.offsetByCodePoints(0, pos), s);
                    t.insert(pos, s);
                } else if (op == 1 && len > 0) {
                    int pos = r.nextInt(len);
                    int n = 1 + r.nextInt(len - pos);
                    int from = model.offsetByCodePoints(0, pos);
                    int to = model.offsetByCodePoints(from, n);
                    String removed = model.substring(from, to);
                    model.delete(from, to);
                    assertEquals(removed, t.delete(pos, n));
                } else if (len > 0) {
                    int pos = r.nextInt(len + 1);
                    int n = r.nextInt(len - pos + 1);
                    int from = model.offsetByCodePoints(0, pos);
                    int to = model.offsetByCodePoints(from, n);
                    assertEquals(model.substring(from, to), t.substring(pos, pos + n));
                }
                assertEquals(model.toString(), t.text(), "seed " + seed + " step " + i);
                assertTrue(t.invariantsHold());
                assertEquals(model.codePointCount(0, model.length()), t.length());
            }
        }
    }

    /** Picks a random insert position, favouring the edges 0 and the end. */
    private static int pick(Random r, int len) {
        int k = r.nextInt(6);
        return k == 0 ? 0 : k == 1 ? len : r.nextInt(len + 1);
    }
}
