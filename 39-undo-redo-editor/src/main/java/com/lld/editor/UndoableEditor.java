package com.lld.editor;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The editor: a {@link TextBuffer}, a cursor, two stacks of {@link Command}s, coalescing and a history cap.
 *
 * <p>Flow of an edit: validate the arguments, build the command, decide whether it merges into the previous step, apply it to the buffer, clear redo, push (or replace the top), drop the oldest step if over the cap. Validation comes first, so a rejected edit changes nothing.
 *
 * <p>Coalescing rules. A new single code point merges into the top step when all of these hold: the previous edit left the step open (no undo, redo or {@link #breakCoalescing()} since, and it was a single code point, so a paste is sealed), the pause since the previous edit is at most the limit, it is the same kind (insert with insert, delete with delete), and it continues the run (insert at the end of the last insert; delete at the same position or one before). A typed letter after whitespace starts a new step, so each word is its own step.
 *
 * <p>History cap. At most {@code maxSteps} steps are kept; the oldest is dropped first. A cap counts steps, not bytes, so one huge paste is still one step. Eviction only happens on a new edit, and a new edit has just cleared redo, so eviction never touches redo. The redo stack can only hold steps that came off the undo stack, so it is bounded by the cap too.
 *
 * <p>Not thread safe: one editing thread.
 */
public final class UndoableEditor implements TextEditor {

    private final TextBuffer buffer;
    private final Ticker ticker;
    private final long pauseNanos;
    private final int maxSteps;
    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();
    private int cursor;
    private boolean open;
    private long lastEditNanos;

    /**
     * Creates an editor.
     *
     * @param buffer   the storage, already holding the starting text
     * @param ticker   elapsed-time source for the pause rule
     * @param pause    the longest gap between two keystrokes that still merge (a longer gap starts a new step)
     * @param maxSteps how many undo steps to keep, at least 1
     */
    public UndoableEditor(TextBuffer buffer, Ticker ticker, Duration pause, int maxSteps) {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be at least 1");
        }
        this.buffer = buffer;
        this.ticker = ticker;
        this.pauseNanos = pause.toNanos();
        this.maxSteps = maxSteps;
        this.cursor = buffer.length();
    }

    /** Convenience: a piece-table editor holding {@code initial}, with the cursor at its end. */
    public static UndoableEditor of(String initial, Ticker ticker, Duration pause, int maxSteps) {
        return new UndoableEditor(new PieceTable(initial), ticker, pause, maxSteps);
    }

    /** Validates, then records and applies an insert. */
    @Override
    public void insert(int pos, String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        checkPos(pos);
        Command c = new Command.Insert(pos, text);
        record(c, false);
        cursor = pos + c.cpLen();
    }

    /** Validates, removes the text (the buffer hands back what it removed, so undo can restore it with one read), then records the delete. */
    @Override
    public void delete(int pos, int len) {
        if (len <= 0) {
            throw new IllegalArgumentException("len must be positive");
        }
        checkPos(pos);
        if (len > buffer.length() - pos) {
            throw new IndexOutOfBoundsException("delete " + pos + "+" + len + " past the end " + buffer.length());
        }
        String removed = buffer.delete(pos, len);
        record(new Command.Delete(pos, removed), true);
        cursor = pos;
    }

    /** Deletes one code point before the cursor, or returns false at position 0. */
    @Override
    public boolean backspace() {
        if (cursor == 0) {
            return false;
        }
        delete(cursor - 1, 1);
        return true;
    }

    /** Moves the cursor after checking the range. It does not close the step by itself: a jump is detected because the next edit is not at the run's end. */
    @Override
    public void moveCursor(int pos) {
        checkPos(pos);
        cursor = pos;
    }

    /** Closes the open step. */
    @Override
    public void breakCoalescing() {
        open = false;
    }

    /** Pops the latest step, reverses it, parks it on redo and closes the open step. The cursor goes to where the edit happened, except after undoing a delete: it goes to the end of the restored text, which is where a backspace left it but is not where a forward-delete run started. */
    @Override
    public boolean undo() {
        Command c = undoStack.pollLast();
        if (c == null) {
            return false;
        }
        c.revert(buffer);
        redoStack.addLast(c);
        cursor = c instanceof Command.Insert ? c.pos() : c.pos() + c.cpLen();
        open = false;
        return true;
    }

    /** Pops the latest undone step, applies it again and puts it back on the undo stack. */
    @Override
    public boolean redo() {
        Command c = redoStack.pollLast();
        if (c == null) {
            return false;
        }
        c.apply(buffer);
        undoStack.addLast(c);
        cursor = c instanceof Command.Insert ? c.pos() + c.cpLen() : c.pos();
        open = false;
        return true;
    }

    /** Returns the whole text. */
    @Override
    public String text() {
        return buffer.text();
    }

    /** Returns the cursor position. */
    @Override
    public int cursor() {
        return cursor;
    }

    /** Returns the number of undoable steps. */
    @Override
    public int undoDepth() {
        return undoStack.size();
    }

    /** Returns the number of redoable steps. */
    @Override
    public int redoDepth() {
        return redoStack.size();
    }

    /** Records an already validated command: apply it unless the buffer already did, then merge into the top step or push a new one, clear redo, enforce the cap. */
    private void record(Command c, boolean alreadyApplied) {
        long now = ticker.read();
        Command top = undoStack.peekLast();
        Command merged = open && top != null && now - lastEditNanos <= pauseNanos ? merge(top, c) : null;
        if (!alreadyApplied) {
            c.apply(buffer);
        }
        redoStack.clear();
        if (merged != null) {
            undoStack.pollLast();
            undoStack.addLast(merged);
        } else {
            undoStack.addLast(c);
            while (undoStack.size() > maxSteps) {
                undoStack.pollFirst();
            }
        }
        open = c.cpLen() == 1;
        lastEditNanos = now;
    }

    /**
     * Returns the single command equal to {@code last} followed by {@code next}, or null when they must stay separate steps.
     *
     * <p>Deletes have no word rule (only inserts do). A backspace and a forward delete can merge into one step. Joining texts copies the run each time, so a run of L keys costs O(L squared) characters; fine for typing bursts, not for a megabyte of held-down backspace.
     */
    private static Command merge(Command last, Command next) {
        if (next.cpLen() != 1) {
            return null;
        }
        if (last instanceof Command.Insert && next instanceof Command.Insert) {
            boolean continues = next.pos() == last.pos() + last.cpLen();
            int lastCp = last.text().codePointBefore(last.text().length());
            int nextCp = next.text().codePointAt(0);
            boolean newWord = Character.isWhitespace(lastCp) && !Character.isWhitespace(nextCp);
            return continues && !newWord ? new Command.Insert(last.pos(), last.text() + next.text()) : null;
        }
        if (last instanceof Command.Delete && next instanceof Command.Delete) {
            if (next.pos() == last.pos()) {
                return new Command.Delete(last.pos(), last.text() + next.text());
            }
            if (next.pos() + 1 == last.pos()) {
                return new Command.Delete(next.pos(), next.text() + last.text());
            }
        }
        return null;
    }

    /** Throws {@link IndexOutOfBoundsException} unless {@code pos} is between 0 and the text length. */
    private void checkPos(int pos) {
        if (pos < 0 || pos > buffer.length()) {
            throw new IndexOutOfBoundsException("position " + pos + " not in 0.." + buffer.length());
        }
    }
}
