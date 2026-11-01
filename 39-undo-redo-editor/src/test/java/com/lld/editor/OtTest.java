package com.lld.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Tests of the tiny operational transform: both sites must end with the same text (TP1). */
class OtTest {

    /** Applies {@code first} then the transformed {@code second} to a fresh buffer and returns the text. */
    private static String run(String start, Ot.Op first, Ot.Op second) {
        PieceTable t = new PieceTable(start);
        Ot.apply(t, first);
        for (Ot.Op o : Ot.transform(second, first)) {
            Ot.apply(t, o);
        }
        return t.text();
    }

    /** Proves the README example: Ann inserts at 0, Bob deletes, and both orders converge. */
    @Test
    void workedExample() {
        Ot.Op ann = new Ot.Ins(0, "X", 1);
        Ot.Op bob = new Ot.Del(2, 2);
        assertEquals("Xabef", run("abcdef", ann, bob));
        assertEquals("Xabef", run("abcdef", bob, ann));
    }

    /** Proves an insert inside a range that the other site deleted splits the delete in two and still converges. */
    @Test
    void insertInsideDelete() {
        Ot.Op ins = new Ot.Ins(3, "--", 1);
        Ot.Op del = new Ot.Del(1, 4);
        assertEquals(run("abcdef", ins, del), run("abcdef", del, ins));
    }

    /** Checks convergence for thousands of random pairs of ops, including ties, overlaps and empty-ish documents. */
    @Test
    void randomPairsConverge() {
        Random r = new Random(7);
        for (int i = 0; i < 20000; i++) {
            String start = "0123456789".substring(0, r.nextInt(11));
            Ot.Op a = randomOp(r, start.length(), 1);
            Ot.Op b = randomOp(r, start.length(), 2);
            assertEquals(run(start, a, b), run(start, b, a), start + " " + a + " " + b);
        }
    }

    /** Builds a random insert or delete valid for a text of length {@code len}. */
    private static Ot.Op randomOp(Random r, int len, int site) {
        if (len == 0 || r.nextBoolean()) {
            return new Ot.Ins(r.nextInt(len + 1), site == 1 ? "AB" : "c", site);
        }
        int pos = r.nextInt(len);
        return new Ot.Del(pos, 1 + r.nextInt(len - pos));
    }

    /** Proves a delete swallowed by the other site's delete becomes no operation at all. */
    @Test
    void swallowedDeleteVanishes() {
        assertEquals(List.of(), Ot.transform(new Ot.Del(2, 1), new Ot.Del(1, 4)));
    }
}
