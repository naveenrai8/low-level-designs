package com.lld.spreadsheet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The spreadsheet engine.
 *
 * <p>State: the text and parsed formula of each non-empty cell, the current value of each, and the dependency graph stored twice: {@code precedents} (cell to the cells it reads) and {@code dependents} (cell to the cells that read it). Both are kept in step on every edit. Sorted maps and sets are used everywhere so iteration order is the same on every run.
 *
 * <p>An edit does these steps in this order: parse, find the dirty set, check for a cycle with it, swap the edges, recompute the dirty set. Everything that can fail happens before the first write.
 *
 * <p>Thread model: single writer. The class is not thread-safe, so call every method from one thread (or under one external lock). To let other threads read, call {@link #snapshot()} on the writer thread and hand the returned immutable map over through any safe hand-off (a volatile field, a queue, a thread start or join).
 */
public final class Sheet implements Spreadsheet {

    /** A parsed non-empty cell. */
    private record Cell(String raw, Expr expr) { }

    private final Grid grid;
    private final Map<CellId, Cell> cells = new TreeMap<>();
    private final Map<CellId, Value> values = new TreeMap<>();
    private final Map<CellId, TreeSet<CellId>> precedents = new TreeMap<>();
    private final Map<CellId, TreeSet<CellId>> dependents = new TreeMap<>();
    private final Deque<Edit> undoStack = new ArrayDeque<>();
    private final Deque<Edit> redoStack = new ArrayDeque<>();
    private static final Set<CellId> EMPTY = Collections.emptySortedSet();

    private long evaluations;
    private long graphSteps;
    private List<CellId> lastOrder = List.of();

    /**
     * Makes an empty sheet.
     *
     * @param grid the size
     */
    public Sheet(Grid grid) {
        this.grid = grid;
    }

    /** Makes an empty A1 to Z99 sheet. */
    public Sheet() {
        this(Grid.DEFAULT);
    }

    /** Sets a cell, then updates everything that depends on it (see the interface for the error contract). */
    @Override
    public void set(String cell, String raw) {
        CellId id = CellId.parse(cell, grid);
        String old = rawOf(id);
        apply(id, raw);
        undoStack.push(new Edit(id, old, raw));
        redoStack.clear();
    }

    /** Reads the current value of a cell; an empty cell is 0. */
    @Override
    public Value value(String cell) {
        return values.getOrDefault(CellId.parse(cell, grid), Value.ZERO);
    }

    /** Reads the text typed into a cell, or "" when empty. */
    @Override
    public String raw(String cell) {
        return rawOf(CellId.parse(cell, grid));
    }

    /** Reverts the latest edit, restoring text, values and dependency edges. */
    @Override
    public boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        Edit e = undoStack.pop();
        apply(e.cell(), e.oldRaw());
        redoStack.push(e);
        return true;
    }

    /** Re-applies the latest undone edit. */
    @Override
    public boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        Edit e = redoStack.pop();
        apply(e.cell(), e.newRaw());
        undoStack.push(e);
        return true;
    }

    /** Copies all non-empty values into an immutable sorted map for readers on other threads. */
    @Override
    public SortedMap<CellId, Value> snapshot() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    /**
     * How many cell evaluations this sheet has done in its life. Tests read the difference before and after an edit to prove only the downstream cells were recomputed.
     *
     * @return the running count
     */
    public long evaluations() {
        return evaluations;
    }

    /**
     * The cells evaluated by the latest recompute, in the order they were evaluated (a topological order).
     *
     * @return an immutable copy
     */
    public List<CellId> lastRecomputeOrder() {
        return lastOrder;
    }

    /**
     * Throws away the incremental idea and recomputes every cell in topological order. Used by the benchmark as the "full recompute" baseline; the differential test uses a separate, independent reference instead.
     */
    public void recomputeAll() {
        recompute(new LinkedHashSet<>(cells.keySet()));
    }

    /**
     * Lists what a cell reads, for tests.
     *
     * @param cell the cell name
     * @return the precedents in cell order
     */
    public Set<CellId> precedentsOf(String cell) {
        return Collections.unmodifiableSet(reads(CellId.parse(cell, grid)));
    }

    /**
     * Lists what reads a cell, for tests.
     *
     * @param cell the cell name
     * @return the dependents in cell order
     */
    public Set<CellId> dependentsOf(String cell) {
        return Collections.unmodifiableSet(readers(CellId.parse(cell, grid)));
    }

    /**
     * Checks that the two edge maps are exact mirrors of each other and of the stored formulas, with no empty sets lying around.
     *
     * @return true when consistent
     */
    public boolean edgesMatchFormulas() {
        Map<CellId, TreeSet<CellId>> expectP = new TreeMap<>();
        Map<CellId, TreeSet<CellId>> expectD = new TreeMap<>();
        for (Map.Entry<CellId, Cell> e : cells.entrySet()) {
            TreeSet<CellId> refs = e.getValue().expr().references();
            if (refs.isEmpty()) {
                continue;
            }
            expectP.put(e.getKey(), refs);
            for (CellId r : refs) {
                expectD.computeIfAbsent(r, k -> new TreeSet<>()).add(e.getKey());
            }
        }
        return expectP.equals(precedents) && expectD.equals(dependents);
    }

    /**
     * The text of a cell.
     *
     * @param id the cell
     * @return the raw text or ""
     */
    private String rawOf(CellId id) {
        Cell c = cells.get(id);
        return c == null ? "" : c.raw();
    }

    /**
     * The shared edit path used by set, undo and redo: parse, reject cycles, swap edges, recompute the downstream cells.
     *
     * @param id the cell
     * @param raw the new text
     */
    private void apply(CellId id, String raw) {
        Parsed parsed = Parser.parse(raw, grid);
        TreeSet<CellId> newRefs = parsed.isBlank() ? new TreeSet<>() : parsed.expr().references();
        Map<CellId, CellId> dirty = dirtySet(id);
        if (!newRefs.isEmpty()) {
            List<CellId> cycle = findCycle(id, newRefs, dirty);
            if (cycle != null) {
                throw new CycleException(cycle);
            }
        }
        // From here on nothing can fail, so the sheet is never left half-edited.
        for (CellId old : reads(id)) {
            unlink(old, id);
        }
        precedents.remove(id);
        if (parsed.isBlank()) {
            cells.remove(id);
            values.remove(id);
        } else {
            cells.put(id, new Cell(raw, parsed.expr()));
            if (!newRefs.isEmpty()) {
                precedents.put(id, newRefs);
                for (CellId p : newRefs) {
                    dependents.computeIfAbsent(p, k -> new TreeSet<>()).add(id);
                }
            }
        }
        recompute(dirty.keySet());
    }

    /**
     * Removes the edge "dependent reads precedent" from the forward map, dropping the set if it becomes empty.
     *
     * @param precedent the cell that was read
     * @param dependent the cell that read it
     */
    private void unlink(CellId precedent, CellId dependent) {
        TreeSet<CellId> set = dependents.get(precedent);
        set.remove(dependent);
        if (set.isEmpty()) {
            dependents.remove(precedent);
        }
    }

    /**
     * Finds the shortest cycle the new formula of {@code id} would create, using the dirty set that was already computed for the recompute. The stored graph has no cycle, so a cycle appears exactly when the new formula reads a cell that is downstream of {@code id} (a cell that already depends on it, or {@code id} itself). The dirty set is a breadth-first walk, so its keys come in order of distance from {@code id} and each cell remembers the cell it was reached from. The first key that the new formula reads is therefore the closest one, and following the remembered cells back gives the shortest path.
     *
     * @param id the cell being edited
     * @param newRefs what its new formula would read, not empty
     * @param dirty the dirty set of {@code id}: cell to the cell it was reached from, in breadth-first order
     * @return the cycle as "id, ..., id" where each cell reads the next, or null when there is none
     */
    private List<CellId> findCycle(CellId id, TreeSet<CellId> newRefs, Map<CellId, CellId> dirty) {
        for (CellId hit : dirty.keySet()) {
            if (newRefs.contains(hit)) {
                // id reads hit; hit reads the cell it was reached from; and so on back to a cell that reads id.
                List<CellId> out = new ArrayList<>();
                out.add(id);
                for (CellId c = hit; !c.equals(id); c = dirty.get(c)) {
                    out.add(c);
                }
                out.add(id);
                return out;
            }
        }
        return null;
    }

    /**
     * The dirty set: the edited cell plus everything that reads it, directly or through others. Found by a breadth-first walk over the forward edges, so the keys come in order of distance. Each cell maps to the cell it was first reached from (the edited cell maps to null). The set is the same before and after the edit swaps the edited cell's own edges, because those edges only point into the edited cell.
     *
     * @param start the edited cell
     * @return reached cell to the cell it was reached from, in discovery order
     */
    private Map<CellId, CellId> dirtySet(CellId start) {
        Map<CellId, CellId> seen = new LinkedHashMap<>();
        Deque<CellId> queue = new ArrayDeque<>();
        seen.put(start, null);
        queue.add(start);
        while (!queue.isEmpty()) {
            CellId cur = queue.poll();
            for (CellId d : readers(cur)) {
                graphSteps++;
                if (!seen.containsKey(d)) {
                    seen.put(d, cur);
                    queue.add(d);
                }
            }
        }
        return seen;
    }

    /**
     * The cells a cell reads, never null.
     *
     * @param id the cell
     * @return its precedents in cell order
     */
    private Set<CellId> reads(CellId id) {
        TreeSet<CellId> s = precedents.get(id);
        return s == null ? EMPTY : s;
    }

    /**
     * The cells that read a cell, never null.
     *
     * @param id the cell
     * @return its dependents in cell order
     */
    private Set<CellId> readers(CellId id) {
        TreeSet<CellId> s = dependents.get(id);
        return s == null ? EMPTY : s;
    }

    /**
     * How many forward edges the dirty-set walks have looked at in this sheet's life. Tests use it to prove the walk touches each edge once.
     *
     * @return the running count
     */
    long graphSteps() {
        return graphSteps;
    }

    /**
     * Recomputes the given cells with Kahn's algorithm: count, for each cell, how many of its precedents are also in the set; evaluate the cells with count zero; each time a cell is done, lower the count of its dependents and queue those that reach zero. Cost is O(cells + edges inside the set).
     *
     * @param dirty the cells to recompute; must be closed under "is read by" and the graph must be acyclic
     */
    private void recompute(Set<CellId> dirty) {
        Map<CellId, Integer> waiting = new HashMap<>();
        Deque<CellId> ready = new ArrayDeque<>();
        for (CellId c : dirty) {
            int n = 0;
            for (CellId p : reads(c)) {
                if (dirty.contains(p)) {
                    n++;
                }
            }
            waiting.put(c, n);
            if (n == 0) {
                ready.add(c);
            }
        }
        List<CellId> order = new ArrayList<>(dirty.size());
        while (!ready.isEmpty()) {
            CellId c = ready.poll();
            order.add(c);
            Cell cell = cells.get(c);
            if (cell != null) {
                evaluations++;
                values.put(c, Evaluator.eval(cell.expr(), id -> values.getOrDefault(id, Value.ZERO)));
            }
            for (CellId d : readers(c)) {
                Integer left = waiting.get(d);
                if (left != null && left - 1 == 0) {
                    ready.add(d);
                }
                if (left != null) {
                    waiting.put(d, left - 1);
                }
            }
        }
        if (order.size() != dirty.size()) {
            throw new IllegalStateException("graph has a cycle that was not rejected");
        }
        order.removeIf(c -> !cells.containsKey(c));
        lastOrder = List.copyOf(order);
    }
}
