package com.lld.jiralite;

import java.util.Set;

/**
 * What the search index stores for one issue: the fields it can filter on plus the free text.
 *
 * @param id the issue key
 * @param project the project key
 * @param status the workflow state
 * @param assignee the assignee, or empty text for none
 * @param labels lower-case labels
 * @param text title plus comments, searched word by word
 */
public record IssueDoc(String id, String project, String status, String assignee, Set<String> labels, String text) {

    /** Copies the labels so the record cannot change after it is built. */
    public IssueDoc {
        labels = Set.copyOf(labels);
    }
}
