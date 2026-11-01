package com.lld.spreadsheet;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A parsed formula: a small tree. Built once when the formula is set, then evaluated many times.
 */
public sealed interface Expr {

    /**
     * A whole-number literal.
     *
     * @param value the number (a formula literal is never negative, minus is {@link Neg}; a typed-in literal may be)
     */
    record Num(long value) implements Expr { }

    /**
     * A reference to one cell.
     *
     * @param id the cell
     */
    record Ref(CellId id) implements Expr { }

    /** A reference that points outside the grid. It evaluates to #REF! and creates no edge. */
    record BadRef() implements Expr { }

    /**
     * Unary minus.
     *
     * @param operand what is negated
     */
    record Neg(Expr operand) implements Expr { }

    /**
     * A binary operation.
     *
     * @param op one of + - * /
     * @param left left side
     * @param right right side
     */
    record Bin(char op, Expr left, Expr right) implements Expr { }

    /**
     * SUM over a rectangle.
     *
     * @param from top-left corner
     * @param to bottom-right corner
     */
    record Sum(CellId from, CellId to) implements Expr {

        /**
         * Lists every cell of the rectangle in column-major order (A1, A2, B1, B2 ...), the order errors are reported in.
         *
         * @return the cells
         */
        public List<CellId> cells() {
            List<CellId> out = new ArrayList<>();
            for (int c = from.col(); c <= to.col(); c++) {
                for (int r = from.row(); r <= to.row(); r++) {
                    out.add(new CellId(c, r));
                }
            }
            return out;
        }
    }

    /**
     * Collects the cells this formula reads: its precedents. A range contributes every cell in it.
     *
     * @return the cells in sorted order, without duplicates
     */
    default TreeSet<CellId> references() {
        TreeSet<CellId> out = new TreeSet<>();
        collect(this, out);
        return out;
    }

    /**
     * Walks the tree and adds every referenced cell.
     *
     * @param e the node to walk
     * @param out where cells are added
     */
    private static void collect(Expr e, TreeSet<CellId> out) {
        switch (e) {
            case Ref r -> out.add(r.id());
            case Neg n -> collect(n.operand(), out);
            case Bin b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
            case Sum s -> out.addAll(s.cells());
            case Num n -> { }
            case BadRef b -> { }
        }
    }
}
