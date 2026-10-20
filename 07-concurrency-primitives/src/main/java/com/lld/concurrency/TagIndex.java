package com.lld.concurrency;

import java.util.Set;

/** tag → items, written and read concurrently. */
public interface TagIndex {

    void tag(String tag, String item);

    /** An immutable snapshot; later writes don't show up in it. */
    Set<String> itemsTagged(String tag);
}
