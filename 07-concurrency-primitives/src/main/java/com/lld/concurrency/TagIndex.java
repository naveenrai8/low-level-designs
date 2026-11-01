package com.lld.concurrency;

import java.util.Set;

/**
 * Maps a tag to its items, written and read from many threads at once.
 */
public interface TagIndex {

    /**
     * Files the item under the tag.
     */
    void tag(String tag, String item);

    /**
     * Returns an immutable snapshot of the tag's items. Later writes do not show up in it.
     */
    Set<String> itemsTagged(String tag);
}
