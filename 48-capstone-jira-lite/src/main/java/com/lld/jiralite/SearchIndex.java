package com.lld.jiralite;

import java.util.List;

/** The search seam. The service keeps this index in step with committed state; swapping in a real engine (Lucene, Elasticsearch) means writing another implementation of these three methods. */
public interface SearchIndex {

    /** Adds the document, or replaces the old one with the same id completely (old words must disappear). */
    void put(IssueDoc doc);

    /** Forgets the issue; unknown ids are ignored. */
    void remove(String id);

    /** Returns the ids of matching documents in ascending id order. Only documents whose project is in the query's allow-list may come back. */
    List<String> search(Query query);
}
