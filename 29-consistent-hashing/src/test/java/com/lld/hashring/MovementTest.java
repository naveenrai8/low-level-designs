package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Proves that adding or removing a node moves only the keys it must, and measures how many that is. */
class MovementTest {

    private static final int KEYS = 100_000;

    /** Builds the key list "key-0" ... once so every run sees the same keys. */
    private static String[] keys() {
        String[] k = new String[KEYS];
        for (int i = 0; i < KEYS; i++) {
            k[i] = "key-" + i;
        }
        return k;
    }

    /** Adding a node: every key that moves moves TO the new node, and none moves between old nodes. Checked for every key. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void addedNodeOnlyReceivesKeys(LocatorFactory f) {
        Locator l = f.create(11);
        for (int i = 0; i < 10; i++) {
            l.addNode("n" + i);
        }
        String[] keys = keys();
        String[] before = new String[KEYS];
        for (int i = 0; i < KEYS; i++) {
            before[i] = l.locate(keys[i]);
        }
        l.addNode("new");
        int moved = 0;
        for (int i = 0; i < KEYS; i++) {
            String after = l.locate(keys[i]);
            if (!after.equals(before[i])) {
                assertEquals("new", after, "key moved between old nodes: " + keys[i]);
                moved++;
            }
        }
        assertTrue(moved > 0);
    }

    /** Removing a node: exactly the keys it owned move, and every other key stays put. Checked for every key. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void removedNodeOnlyLosesItsOwnKeys(LocatorFactory f) {
        Locator l = f.create(12);
        for (int i = 0; i < 10; i++) {
            l.addNode("n" + i);
        }
        String[] keys = keys();
        String[] before = new String[KEYS];
        for (int i = 0; i < KEYS; i++) {
            before[i] = l.locate(keys[i]);
        }
        l.removeNode("n3");
        for (int i = 0; i < KEYS; i++) {
            String after = l.locate(keys[i]);
            if (before[i].equals("n3")) {
                assertNotEquals("n3", after);
            } else {
                assertEquals(before[i], after, "unrelated key moved: " + keys[i]);
            }
        }
    }

    /** Raising a node's weight moves keys only TO that node; lowering it moves keys only FROM that node. Checked for every key, for both implementations. */
    @ParameterizedTest
    @EnumSource(LocatorFactory.class)
    void reweightMovesKeysOnlyToOrFromThatNode(LocatorFactory f) {
        Locator l = f.create(13);
        for (int i = 0; i < 8; i++) {
            l.addNode("n" + i, 2);
        }
        String[] keys = keys();
        String[] base = owners(l, keys);
        l.addNode("n3", 6);
        String[] raised = owners(l, keys);
        int toCount = 0;
        for (int i = 0; i < KEYS; i++) {
            if (!raised[i].equals(base[i])) {
                assertEquals("n3", raised[i], "raise moved a key somewhere else: " + keys[i]);
                toCount++;
            }
        }
        assertTrue(toCount > 0);
        l.addNode("n3", 1);
        String[] lowered = owners(l, keys);
        int fromCount = 0;
        for (int i = 0; i < KEYS; i++) {
            if (!lowered[i].equals(raised[i])) {
                assertEquals("n3", raised[i], "lower moved a key that n3 did not own: " + keys[i]);
                fromCount++;
            }
        }
        assertTrue(fromCount > 0);
    }

    /** Returns the owner of each key. */
    private static String[] owners(Locator l, String[] keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = l.locate(keys[i]);
        }
        return out;
    }

    /** Fraction of keys that change owner when going from n nodes to n+1. */
    private static double movedFractionAdd(LocatorFactory f, int n) {
        Locator l = f.create(7);
        for (int i = 0; i < n; i++) {
            l.addNode("n" + i);
        }
        String[] keys = keys();
        String[] before = new String[KEYS];
        for (int i = 0; i < KEYS; i++) {
            before[i] = l.locate(keys[i]);
        }
        l.addNode("n" + n);
        int moved = 0;
        for (int i = 0; i < KEYS; i++) {
            if (!l.locate(keys[i]).equals(before[i])) {
                moved++;
            }
        }
        return moved / (double) KEYS;
    }

    /** Fraction of keys that change owner under naive hash-mod-n when going from n to n+1 buckets. */
    private static double movedFractionMod(int n) {
        int moved = 0;
        for (String k : keys()) {
            long h = Hash64.hash(k, 7);
            if (Long.remainderUnsigned(h, n) != Long.remainderUnsigned(h, n + 1)) {
                moved++;
            }
        }
        return moved / (double) KEYS;
    }

    /** Measures the moved fraction for n = 2, 5, 10, 20, 50, checks ring and rendezvous are within 20% (relative) of the ideal 1/(n+1) and naive mod is near n/(n+1). Prints the table the README quotes. */
    @Test
    void movedFractionMatchesTheory() {
        System.out.println("MOVEMENT keys=" + KEYS + " seed=7 ring=200 vnodes/node");
        System.out.println("MOVEMENT n -> n+1 | ideal 1/(n+1) | ring | rendezvous | hash mod n");
        for (int n : new int[] {2, 5, 10, 20, 50}) {
            double ideal = 1.0 / (n + 1);
            double ring = movedFractionAdd(LocatorFactory.RING, n);
            double hrw = movedFractionAdd(LocatorFactory.RENDEZVOUS, n);
            double mod = movedFractionMod(n);
            System.out.printf("MOVEMENT %d -> %d | %.4f | %.4f | %.4f | %.4f%n", n, n + 1, ideal, ring, hrw, mod);
            assertEquals(ideal, ring, ideal * 0.20, "ring n=" + n);
            assertEquals(ideal, hrw, ideal * 0.20, "hrw n=" + n);
            assertEquals((double) n / (n + 1), mod, 0.02, "mod n=" + n);
        }
    }
}
