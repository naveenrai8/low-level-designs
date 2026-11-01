package com.lld.spreadsheet;

/**
 * Thrown when the text of an edit is not a valid number or formula. Carries the zero-based position of the problem inside the text that was given (the leading '=' counts as position 0).
 */
public final class FormulaParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final int position;

    /**
     * Builds the error.
     *
     * @param message what is wrong
     * @param position where in the text it is wrong
     */
    public FormulaParseException(String message, int position) {
        super(message + " at position " + position);
        this.position = position;
    }

    /**
     * Where the problem is.
     *
     * @return the zero-based index
     */
    public int position() {
        return position;
    }
}
