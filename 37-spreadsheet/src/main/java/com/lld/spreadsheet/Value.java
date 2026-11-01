package com.lld.spreadsheet;

/**
 * What a cell shows: either a whole number or an error. An error is a value, not an exception, so it flows through formulas to every cell that depends on it.
 *
 * @param number the number, always 0 when this is an error
 * @param error the error kind, or null for a plain number
 */
public record Value(long number, ErrorKind error) {

    /** The value of an empty cell. */
    public static final Value ZERO = new Value(0, null);

    /**
     * A plain number.
     *
     * @param n the number
     * @return the value
     */
    public static Value of(long n) {
        return new Value(n, null);
    }

    /**
     * An error value.
     *
     * @param kind which error
     * @return the value
     */
    public static Value err(ErrorKind kind) {
        return new Value(0, kind);
    }

    /**
     * Says whether this is an error.
     *
     * @return true for an error value
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Shows the number or the error text.
     *
     * @return "42" or "#DIV/0!"
     */
    @Override
    public String toString() {
        return isError() ? error.text() : Long.toString(number);
    }
}
