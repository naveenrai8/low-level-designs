package com.lld.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A differential test of the whole editor (coalescing off by breaking after each edit) against a naive undo model that stores full text snapshots. */
class EditorModelTest {

    /** Runs thousands of random insert, delete, undo and redo operations on several seeds and compares the text after each one. */
    @Test
    void randomOpsMatchSnapshotModel() {
        String[] snippets = {"a", "xy", "😀", "hello", "😁b😂"};
        for (long seed = 1; seed <= 6; seed++) {
            Random r = new Random(seed);
            int cap = 5 + (int) seed * 7;
            UndoableEditor e = UndoableEditor.of("", new FakeTicker(), Duration.ofSeconds(1), cap);
            StringBuilder model = new StringBuilder();
            List<String> undo = new ArrayList<>();
            Deque<String> redo = new ArrayDeque<>();
            for (int i = 0; i < 4000; i++) {
                int len = model.codePointCount(0, model.length());
                int op = r.nextInt(5);
                if (op <= 1) {
                    int pos = r.nextInt(len + 1);
                    String s = snippets[r.nextInt(snippets.length)];
                    push(undo, model.toString(), cap);
                    model.insert(model.offsetByCodePoints(0, pos), s);
                    redo.clear();
                    e.insert(pos, s);
                    e.breakCoalescing();
                } else if (op == 2 && len > 0) {
                    int pos = r.nextInt(len);
                    int n = 1 + r.nextInt(Math.min(4, len - pos));
                    push(undo, model.toString(), cap);
                    int from = model.offsetByCodePoints(0, pos);
                    model.delete(from, model.offsetByCodePoints(from, n));
                    redo.clear();
                    e.delete(pos, n);
                    e.breakCoalescing();
                } else if (op == 3) {
                    boolean ok = e.undo();
                    assertEquals(!undo.isEmpty(), ok);
                    if (ok) {
                        redo.addLast(model.toString());
                        model = new StringBuilder(undo.remove(undo.size() - 1));
                    }
                } else if (op == 4) {
                    boolean ok = e.redo();
                    assertEquals(!redo.isEmpty(), ok);
                    if (ok) {
                        undo.add(model.toString());
                        model = new StringBuilder(redo.pollLast());
                    }
                }
                assertEquals(model.toString(), e.text(), "seed " + seed + " step " + i);
                assertEquals(undo.size(), e.undoDepth());
                assertEquals(redo.size(), e.redoDepth());
            }
        }
    }

    /** Adds a snapshot to the model's undo list, dropping the oldest beyond the cap. */
    private static void push(List<String> undo, String snapshot, int cap) {
        undo.add(snapshot);
        if (undo.size() > cap) {
            undo.remove(0);
        }
    }
}
