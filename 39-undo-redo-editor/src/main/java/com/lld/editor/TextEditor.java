package com.lld.editor;

/**
 * A text editor with a cursor and undo/redo.
 *
 * <p>Positions are code point indexes: a surrogate pair is one position, a grapheme cluster (flag, accent) is several.
 *
 * <p>Error contract:
 * <ul>
 * <li>A bad position or range throws {@link IndexOutOfBoundsException}; empty text or a length of zero or less throws {@link IllegalArgumentException}. Both are thrown <em>before</em> anything changes: the text, the cursor and both stacks stay as they were.
 * <li>{@link #undo()} and {@link #redo()} with nothing to do return {@code false}. They never throw.
 * </ul>
 */
public interface TextEditor {

    /** Inserts text at {@code pos}; the cursor moves to just after it. A new edit clears the redo history. */
    void insert(int pos, String text);

    /** Types text at the cursor. Same as {@code insert(cursor(), text)}. */
    default void type(String text) {
        insert(cursor(), text);
    }

    /** Deletes {@code len} code points at {@code pos}; the cursor moves to {@code pos}. A new edit clears the redo history. */
    void delete(int pos, int len);

    /** Deletes the code point before the cursor (a whole surrogate pair, never half; a flag is two code points, so one backspace leaves half a flag). Returns false and does nothing when the cursor is at 0. */
    boolean backspace();

    /** Moves the cursor without editing. Throws {@link IndexOutOfBoundsException} outside 0 to length. */
    void moveCursor(int pos);

    /** Ends the current undo step, so the next edit starts a new one even if it would have merged. */
    void breakCoalescing();

    /** Reverses the latest step. Returns false when there is nothing to undo. */
    boolean undo();

    /** Re-applies the latest undone step. Returns false when there is nothing to redo. */
    boolean redo();

    /** Returns the whole text. */
    String text();

    /** Returns the cursor position in code points. */
    int cursor();

    /** Returns how many steps can be undone. */
    int undoDepth();

    /** Returns how many steps can be redone. */
    int redoDepth();
}
