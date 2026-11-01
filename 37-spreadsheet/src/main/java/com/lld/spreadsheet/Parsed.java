package com.lld.spreadsheet;

/**
 * The result of parsing one edit: a tree to evaluate, or nothing when the edit clears the cell.
 *
 * @param expr the expression, or null when the text was blank
 */
record Parsed(Expr expr) {

    /**
     * Says whether the edit empties the cell.
     *
     * @return true when there is no expression
     */
    boolean isBlank() {
        return expr == null;
    }
}
