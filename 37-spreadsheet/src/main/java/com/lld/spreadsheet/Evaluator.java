package com.lld.spreadsheet;

import java.util.function.Function;

/**
 * Evaluates one expression tree given a way to read other cells. Pure: no sheet state, no recursion across cells (the sheet feeds it already-computed values).
 *
 * <p>Rules: all arithmetic is exact, an overflow gives #NUM!. Division truncates toward zero, as Java does: 7/2 is 3 and -7/2 is -3. Dividing by zero gives #DIV/0!. An error operand wins: in a binary operation the left error is reported first, in SUM the first error in column-major order. An empty cell reads as 0.
 */
final class Evaluator {

    /** Not instantiable. */
    private Evaluator() { }

    /**
     * Computes the value of an expression.
     *
     * @param e the expression
     * @param read gives the current value of any other cell
     * @return a number or an error value, never an exception
     */
    static Value eval(Expr e, Function<CellId, Value> read) {
        return switch (e) {
            case Expr.Num n -> Value.of(n.value());
            case Expr.Ref r -> read.apply(r.id());
            case Expr.BadRef b -> Value.err(ErrorKind.REF);
            case Expr.Neg n -> {
                Value v = eval(n.operand(), read);
                yield v.isError() ? v : negate(v.number());
            }
            case Expr.Bin b -> {
                Value l = eval(b.left(), read);
                Value r = eval(b.right(), read);
                yield l.isError() ? l : r.isError() ? r : apply(b.op(), l.number(), r.number());
            }
            case Expr.Sum s -> sum(s, read);
        };
    }

    /**
     * Negates a number, or gives #NUM! for the one value that cannot be negated.
     *
     * @param n the number
     * @return the result
     */
    private static Value negate(long n) {
        return n == Long.MIN_VALUE ? Value.err(ErrorKind.NUM) : Value.of(-n);
    }

    /**
     * Applies one operator to two numbers.
     *
     * @param op one of + - * /
     * @param a left
     * @param b right
     * @return the result or an error value
     */
    private static Value apply(char op, long a, long b) {
        try {
            return switch (op) {
                case '+' -> Value.of(Math.addExact(a, b));
                case '-' -> Value.of(Math.subtractExact(a, b));
                case '*' -> Value.of(Math.multiplyExact(a, b));
                default -> b == 0 ? Value.err(ErrorKind.DIV0)
                        : a == Long.MIN_VALUE && b == -1 ? Value.err(ErrorKind.NUM) : Value.of(a / b);
            };
        } catch (ArithmeticException ex) {
            return Value.err(ErrorKind.NUM);
        }
    }

    /**
     * Adds up a range. The first error cell (column-major order) wins over everything; otherwise any overflowing partial sum gives #NUM!.
     *
     * @param s the range
     * @param read how to read cells
     * @return the total or an error value
     */
    private static Value sum(Expr.Sum s, Function<CellId, Value> read) {
        long total = 0;
        boolean overflow = false;
        for (CellId id : s.cells()) {
            Value v = read.apply(id);
            if (v.isError()) {
                return v;
            }
            try {
                total = Math.addExact(total, v.number());
            } catch (ArithmeticException ex) {
                overflow = true;
            }
        }
        return overflow ? Value.err(ErrorKind.NUM) : Value.of(total);
    }
}
