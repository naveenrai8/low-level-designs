package com.lld.spreadsheet;

/**
 * The kinds of error a cell can hold, with the text a real spreadsheet shows.
 */
public enum ErrorKind {
    /** Division by zero. */
    DIV0("#DIV/0!"),
    /** A reference to a cell outside the grid, such as Z100 or A0. */
    REF("#REF!"),
    /** The exact result does not fit in a long. */
    NUM("#NUM!");

    private final String text;

    /**
     * Remembers the display text.
     *
     * @param text what the cell shows
     */
    ErrorKind(String text) {
        this.text = text;
    }

    /**
     * The display text.
     *
     * @return for example "#DIV/0!"
     */
    public String text() {
        return text;
    }
}
