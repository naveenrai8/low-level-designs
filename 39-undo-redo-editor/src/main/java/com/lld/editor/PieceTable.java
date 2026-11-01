package com.lld.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A piece table: the text is never rewritten, it is a list of <em>pieces</em>, each pointing at a slice of one of two buffers.
 *
 * <p>The <em>original</em> buffer holds the text the file started with and never changes. The <em>add</em> buffer only grows at its end: every typed character is appended there. The visible text is the pieces read in order. Inserting in the middle splits one piece in two and adds a new piece in between, so no existing text is copied. Deleting just trims or drops pieces.
 *
 * <p>Buffers hold code points (one {@code int} each), so a piece boundary can never fall inside a surrogate pair. The price is 4 bytes per character.
 *
 * <p>Typing burst: if the new text goes right after a piece that ends exactly at the end of the add buffer, that piece is extended instead of a new one being added. A thousand keystrokes in a row therefore stay one piece.
 *
 * <p>Finding a position scans the piece list, so every operation is O(pieces). A balanced tree (what VS Code uses) makes that O(log pieces); a flat list is simpler and fast while the piece count is small. The add buffer never shrinks, and it also grows on every undo of a delete and every redo of an insert, because those re-insert text by appending a copy. A production version would keep the removed pieces in the command and splice them back with no copy.
 *
 * <p>Not thread safe: one editor thread owns it.
 */
public final class PieceTable implements TextBuffer {

    /** One slice of a buffer. {@code add} says which buffer; the slice is {@code len} code points from {@code start}. Never empty. */
    private record Piece(boolean add, int start, int len) { }

    private final int[] original;
    private int[] addBuf = new int[16];
    private int addSize;
    private List<Piece> pieces = new ArrayList<>();
    private int length;

    /** Creates a table whose starting text is {@code initial}, held as one piece (or none if it is empty). */
    public PieceTable(String initial) {
        original = initial.codePoints().toArray();
        length = original.length;
        if (length > 0) {
            pieces.add(new Piece(false, 0, length));
        }
    }

    /** Returns the number of code points in the text. */
    @Override
    public int length() {
        return length;
    }

    /** Returns how many pieces the text is made of. Tests use it to prove a typing burst does not add a piece per key. */
    public int pieceCount() {
        return pieces.size();
    }

    /** Returns the piece list as text, such as {@code [o0..5][a0..1][o5..11]} (o = original buffer, a = add buffer, ranges are start..end). Tests and the README use it to show the pieces. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Piece p : pieces) {
            sb.append('[').append(p.add() ? 'a' : 'o').append(p.start()).append("..").append(p.start() + p.len()).append(']');
        }
        return sb.toString();
    }

    /** Inserts the text at {@code pos}, extending the last add-buffer piece when the text continues it, otherwise splitting or adding a piece. */
    @Override
    public void insert(int pos, String text) {
        if (pos < 0 || pos > length) {
            throw new IndexOutOfBoundsException("insert position " + pos + " not in 0.." + length);
        }
        int[] cps = text.codePoints().toArray();
        if (cps.length == 0) {
            return;
        }
        int startInAdd = addSize;
        int idx = 0;
        int acc = 0;
        while (idx < pieces.size() && acc + pieces.get(idx).len() <= pos) {
            acc += pieces.get(idx).len();
            idx++;
        }
        boolean atBoundary = acc == pos;
        Piece prev = atBoundary && idx > 0 ? pieces.get(idx - 1) : null;
        appendToAdd(cps);
        if (prev != null && prev.add() && prev.start() + prev.len() == startInAdd) {
            pieces.set(idx - 1, new Piece(true, prev.start(), prev.len() + cps.length));
        } else if (atBoundary) {
            pieces.add(idx, new Piece(true, startInAdd, cps.length));
        } else {
            Piece p = pieces.get(idx);
            int offset = pos - acc;
            pieces.set(idx, new Piece(p.add(), p.start(), offset));
            pieces.add(idx + 1, new Piece(true, startInAdd, cps.length));
            pieces.add(idx + 2, new Piece(p.add(), p.start() + offset, p.len() - offset));
        }
        length += cps.length;
    }

    /** Removes the range by rebuilding the piece list without it: pieces fully inside vanish, pieces touching an edge are trimmed. Returns the removed text. */
    @Override
    public String delete(int pos, int len) {
        if (pos < 0 || len < 0 || len > length - pos) {
            throw new IndexOutOfBoundsException("delete " + pos + "+" + len + " not inside 0.." + length);
        }
        String removed = substring(pos, pos + len);
        if (len == 0) {
            return removed;
        }
        int end = pos + len;
        List<Piece> next = new ArrayList<>(pieces.size() + 1);
        int acc = 0;
        for (Piece p : pieces) {
            int pEnd = acc + p.len();
            if (pEnd <= pos || acc >= end) {
                next.add(p);
            } else {
                if (acc < pos) {
                    next.add(new Piece(p.add(), p.start(), pos - acc));
                }
                if (pEnd > end) {
                    next.add(new Piece(p.add(), p.start() + (end - acc), pEnd - end));
                }
            }
            acc = pEnd;
        }
        pieces = next;
        length -= len;
        return removed;
    }

    /** Reads the code points in {@code [start, end)} by walking the pieces that overlap the range. */
    @Override
    public String substring(int start, int end) {
        if (start < 0 || end < start || end > length) {
            throw new IndexOutOfBoundsException("range " + start + ".." + end + " not inside 0.." + length);
        }
        StringBuilder sb = new StringBuilder(end - start);
        int acc = 0;
        for (Piece p : pieces) {
            int pEnd = acc + p.len();
            if (pEnd > start && acc < end) {
                int from = Math.max(start, acc) - acc;
                int to = Math.min(end, pEnd) - acc;
                int[] buf = p.add() ? addBuf : original;
                for (int i = from; i < to; i++) {
                    sb.appendCodePoint(buf[p.start() + i]);
                }
            }
            acc = pEnd;
        }
        return sb.toString();
    }

    /** Returns true when no piece is empty and the piece lengths add up to the text length. Tests call it after every operation. */
    public boolean invariantsHold() {
        int sum = 0;
        for (Piece p : pieces) {
            if (p.len() <= 0) {
                return false;
            }
            sum += p.len();
        }
        return sum == length;
    }

    /** Copies the code points to the end of the add buffer, doubling its capacity when full. */
    private void appendToAdd(int[] cps) {
        if (addSize + cps.length > addBuf.length) {
            addBuf = Arrays.copyOf(addBuf, Math.max(addBuf.length * 2, addSize + cps.length));
        }
        System.arraycopy(cps, 0, addBuf, addSize, cps.length);
        addSize += cps.length;
    }
}
