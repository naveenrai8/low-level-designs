package com.lld.editor;

/**
 * One undoable edit (the Command pattern): it knows how to apply itself and how to undo itself.
 *
 * <p>Each command stores enough to invert itself. An insert stores the text it added. A delete stores the <em>text it removed</em>, not just a length, because a length cannot bring the characters back.
 */
public sealed interface Command permits Command.Insert, Command.Delete {

    /** Does the edit on the buffer. */
    void apply(TextBuffer buffer);

    /** Exactly reverses {@link #apply}. */
    void revert(TextBuffer buffer);

    /** Returns the code point position where the edit starts. */
    int pos();

    /** Returns the text added (insert) or removed (delete). */
    String text();

    /** Returns the number of code points in {@link #text()}. */
    default int cpLen() {
        return text().codePointCount(0, text().length());
    }

    /** Typing: puts {@code text} at {@code pos}. Undo removes it again. */
    record Insert(int pos, String text) implements Command {

        /** Adds the text at the stored position. */
        @Override
        public void apply(TextBuffer buffer) {
            buffer.insert(pos, text);
        }

        /** Removes the text that apply added. */
        @Override
        public void revert(TextBuffer buffer) {
            buffer.delete(pos, cpLen());
        }
    }

    /** Deleting: removes {@code text} that sat at {@code pos}. Undo puts the saved text back. */
    record Delete(int pos, String text) implements Command {

        /** Removes the stored text from the stored position. */
        @Override
        public void apply(TextBuffer buffer) {
            buffer.delete(pos, cpLen());
        }

        /** Puts the saved text back where it was. */
        @Override
        public void revert(TextBuffer buffer) {
            buffer.insert(pos, text);
        }
    }
}
