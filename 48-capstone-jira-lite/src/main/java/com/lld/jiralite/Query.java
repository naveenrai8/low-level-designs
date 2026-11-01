package com.lld.jiralite;

import java.util.Set;

/**
 * One search. Every filter is optional (null) except {@code projects}, which is the allow-list of projects the asker may browse.
 *
 * @param projects the projects the answer may come from; an empty set means "nothing"
 * @param status only this workflow state, or null
 * @param assignee only this assignee, or null
 * @param label only issues carrying this label, or null
 * @param text every word here must appear in the issue text, or null
 */
public record Query(Set<String> projects, String status, String assignee, String label, String text) {

    /** Refuses a missing allow-list, because "no list" must never be read as "everything". */
    public Query {
        if (projects == null) {
            throw new IllegalArgumentException("projects allow-list must not be null");
        }
        projects = Set.copyOf(projects);
    }
}
