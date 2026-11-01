package com.lld.editor;

import java.util.List;

/**
 * A tiny operational transform (OT) for two people editing the same text (the F3 seam).
 *
 * <p>The problem: Ann and Bob both start from the same text and each makes one edit. Ann's edit reaches Bob after Bob has already done his own. Applying Ann's edit unchanged would hit the wrong place. {@link #transform} rewrites Ann's edit so it means the same thing <em>after</em> Bob's edit. If each side applies the other's rewritten edit, both end with the same text (called TP1, the convergence property). TP2, which matters when three or more sites meet, is not covered. Policy: an insert that lands inside a range the other site deleted is KEPT, and the delete splits around it.
 *
 * <p>This covers one insert or delete against one other. It is not a full system: it has no server, no version numbers and no transform of long histories. Positions are code point indexes. Equal insert positions are tied by site id (the lower id goes first).
 */
public final class Ot {

    /** Not instantiable: this class only holds static functions. */
    private Ot() { }

    /** An edit made at one site (one user). */
    public sealed interface Op permits Ins, Del { }

    /** Insert {@code text} at {@code pos}, made by site {@code site}. */
    public record Ins(int pos, String text, int site) implements Op {

        /** Returns the number of code points inserted. */
        int len() {
            return text.codePointCount(0, text.length());
        }
    }

    /** Delete {@code len} code points from {@code pos}. */
    public record Del(int pos, int len) implements Op { }

    /**
     * Rewrites {@code a} so it can be applied after {@code b} has already been applied. The result is a list because a delete that had an insert land inside it becomes two deletes, and a delete fully swallowed by another becomes none. Apply the list in order.
     */
    public static List<Op> transform(Op a, Op b) {
        if (a instanceof Ins ia && b instanceof Ins ib) {
            boolean shift = ib.pos() < ia.pos() || (ib.pos() == ia.pos() && ib.site() < ia.site());
            return List.of(shift ? new Ins(ia.pos() + ib.len(), ia.text(), ia.site()) : ia);
        }
        if (a instanceof Ins ia && b instanceof Del db) {
            if (ia.pos() <= db.pos()) {
                return List.of(ia);
            }
            int pos = ia.pos() >= db.pos() + db.len() ? ia.pos() - db.len() : db.pos();
            return List.of(new Ins(pos, ia.text(), ia.site()));
        }
        if (a instanceof Del da && b instanceof Ins ib) {
            if (ib.pos() <= da.pos()) {
                return List.of(new Del(da.pos() + ib.len(), da.len()));
            }
            if (ib.pos() >= da.pos() + da.len()) {
                return List.of(da);
            }
            int before = ib.pos() - da.pos();
            return List.of(new Del(da.pos(), before), new Del(da.pos() + ib.len(), da.len() - before));
        }
        Del da = (Del) a;
        Del db = (Del) b;
        int aEnd = da.pos() + da.len();
        int bEnd = db.pos() + db.len();
        int overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(da.pos(), db.pos()));
        int len = da.len() - overlap;
        if (len == 0) {
            return List.of();
        }
        int pos = da.pos() >= bEnd ? da.pos() - db.len() : Math.min(da.pos(), db.pos());
        return List.of(new Del(pos, len));
    }

    /** Applies one op to a buffer. */
    public static void apply(TextBuffer buffer, Op op) {
        if (op instanceof Ins i) {
            buffer.insert(i.pos(), i.text());
        } else {
            Del d = (Del) op;
            buffer.delete(d.pos(), d.len());
        }
    }
}
