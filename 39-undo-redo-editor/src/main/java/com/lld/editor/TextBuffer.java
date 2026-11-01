package com.lld.editor;

/**
 * The text storage behind the editor: a sequence of Unicode code points that can be inserted into and deleted from.
 *
 * <p>All positions are <b>code point</b> indexes, not UTF-16 chars. A surrogate pair (such as the emoji U+1F600) is one position, so an edit never cuts one in half. Grapheme clusters (a flag, a skin-tone emoji, a letter plus accent) are several code points and can still be split; that is out of scope. See {@link java.text.BreakIterator#getCharacterInstance()}.
 *
 * <p>Error contract: a bad position or range throws {@link IndexOutOfBoundsException} and leaves the buffer unchanged. A null text throws {@link NullPointerException}.
 */
public interface TextBuffer {

    /** Returns the number of code points in the text. */
    int length();

    /** Inserts {@code text} so that its first code point ends up at {@code pos}. Valid {@code pos} is 0 to {@link #length()} inclusive. Inserting an empty string does nothing. */
    void insert(int pos, String text);

    /** Removes {@code len} code points starting at {@code pos} and returns the removed text, so the caller can put it back later. */
    String delete(int pos, int len);

    /** Returns the code points from {@code start} (inclusive) to {@code end} (exclusive) as a string. */
    String substring(int start, int end);

    /** Returns the whole text. */
    default String text() {
        return substring(0, length());
    }
}
