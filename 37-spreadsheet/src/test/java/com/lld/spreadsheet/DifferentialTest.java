package com.lld.spreadsheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The differential test: the incremental engine must agree with a from-scratch oracle after every single edit.
 */
class DifferentialTest {

    private static final Grid GRID = new Grid(6);
    private static final int COLS = 4;

    /** Picks a random cell name inside a small 4 by 6 block, small so that cycles and sharing are common. */
    private static String cell(Random r) {
        return new CellId(r.nextInt(COLS), 1 + r.nextInt(GRID.rows())).toString();
    }

    /** Builds a random piece of formula: a number, a cell, a range, or an operation on smaller pieces. */
    private static String piece(Random r, int depth) {
        int k = r.nextInt(depth <= 0 ? 3 : 7);
        return switch (k) {
            case 0 -> Integer.toString(r.nextInt(5));
            case 1, 2 -> r.nextInt(25) == 0 ? "Z99" : cell(r);
            case 3 -> "SUM(" + cell(r) + ":" + cell(r) + ")";
            case 4 -> "(" + piece(r, depth - 1) + ")";
            case 5 -> "-" + piece(r, depth - 1);
            default -> piece(r, depth - 1) + "+-*/".charAt(r.nextInt(4)) + piece(r, depth - 1);
        };
    }

    /** Builds a random edit text: mostly formulas, some numbers, some clears, some huge numbers to force overflow. */
    private static String text(Random r) {
        int k = r.nextInt(10);
        if (k == 0) {
            return "";
        }
        if (k == 1) {
            return Integer.toString(r.nextInt(7));
        }
        if (k == 2) {
            return "9223372036854775807";
        }
        return "=" + piece(r, 3);
    }

    /** Asserts the engine and the oracle hold exactly the same non-empty cells with the same values. */
    private static void sameAs(Sheet sheet, Reference ref, long seed, int step) {
        assertEquals(ref.evaluateAll(), sheet.snapshot(), "seed " + seed + " step " + step);
        assertTrue(sheet.edgesMatchFormulas(), "edges, seed " + seed + " step " + step);
    }

    /** 3000 seeded runs of 40 mixed operations (set, clear, undo, redo, cycle attempts): zero mismatches after every step. */
    @Test
    void incrementalEqualsFromScratchAfterEveryEdit() {
        int cyclesSeen = 0;
        int errorsSeen = 0;
        for (long seed = 0; seed < 3000; seed++) {
            Random r = new Random(seed);
            Sheet sheet = new Sheet(GRID);
            Reference ref = new Reference(GRID);
            List<Map<CellId, String>> undo = new ArrayList<>();
            List<Map<CellId, String>> redo = new ArrayList<>();
            for (int step = 0; step < 40; step++) {
                int op = r.nextInt(10);
                if (op == 0) {
                    boolean did = sheet.undo();
                    assertEquals(!undo.isEmpty(), did);
                    if (did) {
                        redo.add(ref.state());
                        ref.restore(undo.remove(undo.size() - 1));
                    }
                } else if (op == 1) {
                    boolean did = sheet.redo();
                    assertEquals(!redo.isEmpty(), did);
                    if (did) {
                        undo.add(ref.state());
                        ref.restore(redo.remove(redo.size() - 1));
                    }
                } else {
                    String cell = cell(r);
                    String raw = text(r);
                    Map<CellId, String> before = ref.state();
                    boolean ok = ref.trySet(CellId.parse(cell, GRID), raw);
                    if (ok) {
                        sheet.set(cell, raw);
                        undo.add(before);
                        redo.clear();
                    } else {
                        cyclesSeen++;
                        org.junit.jupiter.api.Assertions.assertThrows(CycleException.class, () -> sheet.set(cell, raw));
                    }
                }
                sameAs(sheet, ref, seed, step);
            }
            errorsSeen += (int) sheet.snapshot().values().stream().filter(Value::isError).count();
        }
        assertTrue(cyclesSeen > 1000, "the generator must exercise cycles, saw " + cyclesSeen);
        assertTrue(errorsSeen > 1000, "the generator must exercise error values, saw " + errorsSeen);
    }
}
