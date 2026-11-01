package com.lld.spreadsheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the formula language: what parses, what is rejected and where.
 */
class ParserTest {

    /** Parses a formula with the default grid and returns its tree. */
    private static Expr parse(String raw) {
        return Parser.parse(raw, Grid.DEFAULT).expr();
    }

    /** Returns the error position for text that must be rejected. */
    private static int posOf(String raw) {
        return assertThrows(FormulaParseException.class, () -> Parser.parse(raw, Grid.DEFAULT)).position();
    }

    /** Multiplication binds tighter than addition, and brackets override it. */
    @Test
    void precedenceAndBrackets() {
        Sheet s = new Sheet();
        s.set("A1", "=1+2*3");
        s.set("A2", "=(1+2)*3");
        s.set("A3", "=10-4-3");
        s.set("A4", "=-(2+3)*-2");
        assertEquals(Value.of(7), s.value("A1"));
        assertEquals(Value.of(9), s.value("A2"));
        assertEquals(Value.of(3), s.value("A3"));
        assertEquals(Value.of(10), s.value("A4"));
    }

    /** Spaces are allowed between tokens; a blank text parses to "clear". */
    @Test
    void spacesAndBlank() {
        assertEquals(new Expr.Bin('+', new Expr.Num(1), new Expr.Ref(new CellId(0, 1))), parse("= 1 + A1 "));
        assertTrue(Parser.parse("   ", Grid.DEFAULT).isBlank());
    }

    /** Each malformed input reports the zero-based position of the first bad character. */
    @Test
    void errorsCarryPositions() {
        assertEquals(3, posOf("=1+*2"));
        assertEquals(1, posOf("="));
        assertEquals(5, posOf("=(1+2"));
        assertEquals(4, posOf("=1+2)"));
        assertEquals(1, posOf("=a1"));
        assertEquals(1, posOf("=AA1"));
        assertEquals(1, posOf("=FOO(A1:A2)"));
        assertEquals(8, posOf("=SUM(A1 B2)"));
        assertEquals(1, posOf("=A01"));
        assertEquals(3, posOf("=1 2"));
        assertEquals(0, posOf("hello"));
        assertEquals(2, posOf("12x"));
        assertEquals(1, posOf("-"));
    }

    /** The error message includes the position text. */
    @Test
    void messageNamesPosition() {
        FormulaParseException e = assertThrows(FormulaParseException.class, () -> Parser.parse("=1+*2", Grid.DEFAULT));
        assertTrue(e.getMessage().contains("position 3"), e.getMessage());
    }

    /** A number too big for a long is a parse error, not a crash. */
    @Test
    void hugeLiteralRejected() {
        assertEquals(1, posOf("=99999999999999999999"));
        assertEquals(0, posOf("99999999999999999999"));
        assertEquals(Long.MAX_VALUE, ((Expr.Num) parse("=9223372036854775807")).value());
    }

    /** Absurd nesting and absurd length are rejected instead of overflowing the stack. */
    @Test
    void depthAndLengthLimits() {
        assertThrows(FormulaParseException.class, () -> Parser.parse("=" + "(".repeat(500) + "1" + ")".repeat(500), Grid.DEFAULT));
        assertThrows(FormulaParseException.class, () -> Parser.parse("=" + "-".repeat(500) + "1", Grid.DEFAULT));
        assertThrows(FormulaParseException.class, () -> Parser.parse("=1" + "+1".repeat(600), Grid.DEFAULT));
    }

    /** A cell outside the grid parses and evaluates to #REF!, and so does a range with a bad corner. */
    @Test
    void outOfGridIsRefError() {
        Sheet s = new Sheet();
        s.set("A1", "=Z100");
        s.set("A2", "=SUM(A1:A100)");
        s.set("A3", "=A0");
        s.set("A4", "=A1+1");
        assertEquals(Value.err(ErrorKind.REF), s.value("A1"));
        assertEquals(Value.err(ErrorKind.REF), s.value("A2"));
        assertEquals(Value.err(ErrorKind.REF), s.value("A3"));
        assertEquals(Value.err(ErrorKind.REF), s.value("A4"));
    }

    /** Cell names are validated on the way in. */
    @Test
    void badCellNamesRejected() {
        Sheet s = new Sheet();
        assertThrows(IllegalArgumentException.class, () -> s.set("a1", "1"));
        assertThrows(IllegalArgumentException.class, () -> s.set("A0", "1"));
        assertThrows(IllegalArgumentException.class, () -> s.set("A100", "1"));
        assertThrows(IllegalArgumentException.class, () -> s.set("A01", "1"));
        assertThrows(IllegalArgumentException.class, () -> s.value("AA1"));
    }
}
