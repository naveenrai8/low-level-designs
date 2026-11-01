package com.lld.spreadsheet;

/**
 * Hand-written recursive-descent parser for cell text. No regular expressions.
 *
 * <p>Grammar (a formula starts with '='):
 * <pre>
 * expr    := term (('+' | '-') term)*
 * term    := unary (('*' | '/') unary)*
 * unary   := '-' unary | primary
 * primary := NUMBER | CELL | 'SUM' '(' CELL ':' CELL ')' | '(' expr ')'
 * </pre>
 * <p>Text without '=' must be a whole number such as 42 or -7, with no spaces. Blank text clears the cell. Spaces are allowed between tokens of a formula but not inside a token. Cell names and SUM are upper case only. A literal inside a formula is never negative, so {@code =-9223372036854775808} is a parse error at position 2 (the typed-in number {@code -9223372036854775808} is fine). Every error carries the position of the offending character. A cell outside the grid (Z100, A0) is NOT a parse error: it parses to a {@link Expr.BadRef} and shows #REF! when evaluated.
 */
final class Parser {

    /** Longest accepted text; keeps the expression tree shallow enough for the recursive evaluator. */
    static final int MAX_LENGTH = 1000;

    private static final int MAX_DEPTH = 100;

    private final String s;
    private final Grid grid;
    private int pos;
    private int depth;

    /**
     * Starts a parse of one formula.
     *
     * @param s the whole text including the leading '='
     * @param grid the sheet, used to tell a valid cell from #REF!
     */
    private Parser(String s, Grid grid) {
        this.s = s;
        this.grid = grid;
    }

    /**
     * Parses the text of one edit.
     *
     * @param raw what the user typed: blank, a whole number, or a formula starting with '='
     * @param grid the sheet size
     * @return the parsed edit
     * @throws FormulaParseException with the position of the first problem
     */
    static Parsed parse(String raw, Grid grid) {
        if (raw == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (raw.isBlank()) {
            return new Parsed(null);
        }
        if (raw.length() > MAX_LENGTH) {
            throw new FormulaParseException("text longer than " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        if (raw.charAt(0) != '=') {
            return new Parsed(literal(raw));
        }
        Parser p = new Parser(raw, grid);
        p.pos = 1;
        Expr e = p.expr();
        p.skipSpaces();
        if (p.pos < raw.length()) {
            throw p.fail("unexpected '" + raw.charAt(p.pos) + "'", p.pos);
        }
        return new Parsed(e);
    }

    /**
     * Parses typed-in text that has no '=': an optional minus then digits, nothing else.
     *
     * @param raw the text
     * @return the number as an expression
     */
    private static Expr literal(String raw) {
        int i = raw.charAt(0) == '-' ? 1 : 0;
        if (i == raw.length()) {
            throw new FormulaParseException("expected digits", i);
        }
        for (int k = i; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c < '0' || c > '9') {
                throw new FormulaParseException("expected a number or a formula starting with '='", k);
            }
        }
        try {
            return new Expr.Num(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new FormulaParseException("number does not fit in a long", i);
        }
    }

    /**
     * Builds a parse error at a position.
     *
     * @param msg what is wrong
     * @param at where
     * @return the exception to throw
     */
    private FormulaParseException fail(String msg, int at) {
        return new FormulaParseException(msg, at);
    }

    /** Moves past spaces. */
    private void skipSpaces() {
        while (pos < s.length() && s.charAt(pos) == ' ') {
            pos++;
        }
    }

    /**
     * Parses sums and differences, left to right.
     *
     * @return the tree
     */
    private Expr expr() {
        Expr left = term();
        while (true) {
            skipSpaces();
            char c = pos < s.length() ? s.charAt(pos) : 0;
            if (c != '+' && c != '-') {
                return left;
            }
            pos++;
            left = new Expr.Bin(c, left, term());
        }
    }

    /**
     * Parses products and quotients, left to right.
     *
     * @return the tree
     */
    private Expr term() {
        Expr left = unary();
        while (true) {
            skipSpaces();
            char c = pos < s.length() ? s.charAt(pos) : 0;
            if (c != '*' && c != '/') {
                return left;
            }
            pos++;
            left = new Expr.Bin(c, left, unary());
        }
    }

    /**
     * Parses an optional run of minus signs then a primary.
     *
     * @return the tree
     */
    private Expr unary() {
        skipSpaces();
        if (pos < s.length() && s.charAt(pos) == '-') {
            enter();
            pos++;
            Expr e = new Expr.Neg(unary());
            depth--;
            return e;
        }
        return primary();
    }

    /** Counts one level of nesting and rejects absurdly deep input. */
    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw fail("nested deeper than " + MAX_DEPTH, pos);
        }
    }

    /**
     * Parses a number, a bracketed expression, a cell or SUM(range).
     *
     * @return the tree
     */
    private Expr primary() {
        skipSpaces();
        if (pos >= s.length()) {
            throw fail("expected an expression", pos);
        }
        char c = s.charAt(pos);
        int start = pos;
        if (isDigit(c)) {
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            try {
                return new Expr.Num(Long.parseLong(s.substring(start, pos)));
            } catch (NumberFormatException e) {
                throw fail("number does not fit in a long", start);
            }
        }
        if (c == '(') {
            enter();
            pos++;
            Expr e = expr();
            skipSpaces();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw fail("expected ')'", pos);
            }
            pos++;
            depth--;
            return e;
        }
        if (c >= 'A' && c <= 'Z') {
            int letters = pos;
            while (pos < s.length() && s.charAt(pos) >= 'A' && s.charAt(pos) <= 'Z') {
                pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '(') {
                if (!s.substring(letters, pos).equals("SUM")) {
                    throw fail("unknown function '" + s.substring(letters, pos) + "'", letters);
                }
                return sum();
            }
            pos = start;
            CellId id = cellRef();
            return id == null ? new Expr.BadRef() : new Expr.Ref(id);
        }
        throw fail("unexpected '" + c + "'", pos);
    }

    /**
     * Parses "(A1:B3)" after the word SUM. A corner outside the grid makes the whole range #REF!. Corners may be given in any order.
     *
     * @return the SUM node, or a BadRef
     */
    private Expr sum() {
        pos++;
        skipSpaces();
        CellId a = cellRef();
        skipSpaces();
        if (pos >= s.length() || s.charAt(pos) != ':') {
            throw fail("expected ':' in the range", pos);
        }
        pos++;
        skipSpaces();
        CellId b = cellRef();
        skipSpaces();
        if (pos >= s.length() || s.charAt(pos) != ')') {
            throw fail("expected ')'", pos);
        }
        pos++;
        if (a == null || b == null) {
            return new Expr.BadRef();
        }
        return new Expr.Sum(new CellId(Math.min(a.col(), b.col()), Math.min(a.row(), b.row())),
                new CellId(Math.max(a.col(), b.col()), Math.max(a.row(), b.row())));
    }

    /**
     * Reads one capital letter and its row digits.
     *
     * @return the cell, or null when the syntax is fine but the cell is outside the grid
     */
    private CellId cellRef() {
        int start = pos;
        if (pos >= s.length() || s.charAt(pos) < 'A' || s.charAt(pos) > 'Z') {
            throw fail("expected a cell like A1", pos);
        }
        int col = s.charAt(pos) - 'A';
        pos++;
        int digits = pos;
        while (pos < s.length() && isDigit(s.charAt(pos))) {
            pos++;
        }
        if (digits == pos || s.charAt(digits) == '0' && pos - digits > 1 || pos < s.length() && Character.isLetter(s.charAt(pos))) {
            throw fail("bad cell reference", start);
        }
        long row = pos - digits > 9 ? Long.MAX_VALUE : Long.parseLong(s.substring(digits, pos));
        return row <= grid.rows() && row >= 1 ? new CellId(col, (int) row) : null;
    }

    /**
     * Tells whether a character is 0 to 9.
     *
     * @param c the character
     * @return true for a digit
     */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
