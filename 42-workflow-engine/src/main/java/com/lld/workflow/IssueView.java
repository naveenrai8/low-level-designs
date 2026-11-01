package com.lld.workflow;

import java.util.Map;

/**
 * An immutable snapshot of one issue. It is what the engine stores, what callers get back, and what guards see.
 *
 * <p>The snapshot cannot be changed (the field map is copied into an unmodifiable map), so a guard gets a read-only view. Purity itself is a contract the guard author keeps; the engine also refuses a guard that calls back into the engine.
 *
 * @param id the issue id
 * @param project the project the issue belongs to
 * @param workflowVersion which version of the project's workflow the issue follows
 * @param state the current state name
 * @param version counts committed changes; the compare-and-set token for the next change
 * @param fields free-form text fields set at creation (assignee, author, and so on)
 */
public record IssueView(String id, String project, int workflowVersion, String state, long version, Map<String, String> fields) {

    /** Copies the field map so the snapshot cannot be changed through the map the caller passed in. */
    public IssueView {
        fields = Map.copyOf(fields);
    }

    /** Returns the same issue with a new state, the next version and (optionally) a new workflow version. */
    IssueView moved(String newState, int newWorkflowVersion) {
        return new IssueView(id, project, newWorkflowVersion, newState, version + 1, fields);
    }

    /** Returns the part of this issue that audit replay can rebuild. */
    public IssueState toState() {
        return new IssueState(project, state, version, workflowVersion);
    }
}
