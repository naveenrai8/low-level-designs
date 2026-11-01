package com.lld.spreadsheet;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberately naive spreadsheet used as the oracle in the differential test. It stores only the raw text of each cell, keeps no graph, no dirty set and no cache across edits, and evaluates by plain memoised recursion from scratch every time it is asked. It shares only the pure parser and the pure arithmetic with the real engine, not any of its graph or recompute code.
 */
final class Reference {

    private final Grid grid;
    private final Map<CellId, String> raws = new TreeMap<>();

    /**
     * Makes an empty oracle.
     *
     * @param grid the sheet size
     */
    Reference(Grid grid) {
        this.grid = grid;
    }

    /**
     * Applies an edit if it would not create a cycle.
     *
     * @param id the cell
     * @param raw the new text (already known to parse)
     * @return false when the edit makes a cycle and was therefore not applied
     */
    boolean trySet(CellId id, String raw) {
        String old = raws.get(id);
        put(id, raw);
        if (hasCycle()) {
            put(id, old == null ? "" : old);
            return false;
        }
        return true;
    }

    /**
     * Replaces the whole content, used to mirror undo and redo.
     *
     * @param state cell to text
     */
    void restore(Map<CellId, String> state) {
        raws.clear();
        raws.putAll(state);
    }

    /**
     * Copies the current texts.
     *
     * @return a new map
     */
    Map<CellId, String> state() {
        return new TreeMap<>(raws);
    }

    /**
     * Computes every non-empty cell from scratch.
     *
     * @return cell to value
     */
    Map<CellId, Value> evaluateAll() {
        Map<CellId, Value> memo = new HashMap<>();
        Map<CellId, Value> out = new TreeMap<>();
        for (CellId id : raws.keySet()) {
            out.put(id, valueOf(id, memo));
        }
        return out;
    }

    /**
     * Stores or removes a text.
     *
     * @param id the cell
     * @param raw the text, blank to remove
     */
    private void put(CellId id, String raw) {
        if (raw.isBlank()) {
            raws.remove(id);
        } else {
            raws.put(id, raw);
        }
    }

    /**
     * Looks for a cycle with plain recursion and an "in progress" marker.
     *
     * @return true when any cell reaches itself
     */
    private boolean hasCycle() {
        Map<CellId, Integer> mark = new HashMap<>();
        for (CellId id : raws.keySet()) {
            if (visit(id, mark)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One step of the cycle search: 1 means in progress, 2 means done.
     *
     * @param id the cell
     * @param mark the marks
     * @return true when a cycle was met
     */
    private boolean visit(CellId id, Map<CellId, Integer> mark) {
        Integer m = mark.get(id);
        if (m != null) {
            return m == 1;
        }
        mark.put(id, 1);
        String raw = raws.get(id);
        if (raw != null) {
            for (CellId r : Parser.parse(raw, grid).expr().references()) {
                if (visit(r, mark)) {
                    return true;
                }
            }
        }
        mark.put(id, 2);
        return false;
    }

    /**
     * Evaluates one cell by recursion with a memo for this one pass only.
     *
     * @param id the cell
     * @param memo values computed in this pass
     * @return the value, 0 for an empty cell
     */
    private Value valueOf(CellId id, Map<CellId, Value> memo) {
        Value known = memo.get(id);
        if (known != null) {
            return known;
        }
        String raw = raws.get(id);
        Value v = raw == null ? Value.ZERO : Evaluator.eval(Parser.parse(raw, grid).expr(), c -> valueOf(c, memo));
        memo.put(id, v);
        return v;
    }
}
