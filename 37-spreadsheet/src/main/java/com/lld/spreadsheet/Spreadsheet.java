package com.lld.spreadsheet;

import java.util.SortedMap;

/**
 * A small spreadsheet: cells hold a whole number or a formula, and every formula cell always shows an up-to-date value.
 *
 * <p>Three kinds of failure, kept apart on purpose:
 * <ul>
 * <li>Bad text (not a number, not a formula): {@link FormulaParseException}, with the position. Nothing changes.</li>
 * <li>An edit that would make a circular reference: {@link CycleException}, naming the path. Nothing changes and nothing is added to the undo history.</li>
 * <li>A cell whose formula divides by zero, points outside the grid or overflows: NOT an exception. The cell holds an error {@link Value} and so does every cell that depends on it.</li>
 * </ul>
 * <p>A cell name that is not on the grid (for example "AA1" or "A0") passed to {@code set}, {@code value} or {@code raw} throws {@link IllegalArgumentException}.
 */
public interface Spreadsheet {

    /**
     * Sets a cell and updates everything that depends on it.
     *
     * @param cell the cell name, for example "B7"
     * @param raw blank to clear the cell, a whole number like "42", or a formula like "=A1+SUM(B1:B3)"
     * @throws FormulaParseException when the text is invalid
     * @throws CycleException when the formula would create a cycle
     */
    void set(String cell, String raw);

    /**
     * Reads the current value. An empty cell is the number 0.
     *
     * @param cell the cell name
     * @return the number or error value
     */
    Value value(String cell);

    /**
     * Reads what was typed into the cell.
     *
     * @param cell the cell name
     * @return the raw text, or "" for an empty cell
     */
    String raw(String cell);

    /**
     * Reverts the most recent edit (values and dependency links).
     *
     * @return false when there is nothing to undo
     */
    boolean undo();

    /**
     * Re-applies the most recently undone edit.
     *
     * @return false when there is nothing to redo
     */
    boolean redo();

    /**
     * Copies every non-empty cell's value into an immutable map. Call it on the writer thread; the copy can then be handed to other threads (through a volatile field, a queue or a thread start) and read there while the writer keeps editing.
     *
     * @return cell to value, in cell order
     */
    SortedMap<CellId, Value> snapshot();
}
