package com.lld.ledger;

import java.util.List;

/**
 * A posted journal entry. It never changes: records are immutable and the ledger has no update or delete.
 *
 * @param seq the position in the journal, starting at 1; it only grows
 * @param id the entry id, "e" plus the sequence number
 * @param currency the one currency of every posting
 * @param postings the lines, unmodifiable
 * @param memo a note
 * @param reversesSeq the sequence of the entry this one cancels, or 0 when it reverses nothing
 */
public record Entry(long seq, String id, String currency, List<Posting> postings, String memo, long reversesSeq) {

    /**
     * Copies the postings so the entry cannot be changed through a list the caller still holds.
     */
    public Entry {
        postings = List.copyOf(postings);
    }
}
