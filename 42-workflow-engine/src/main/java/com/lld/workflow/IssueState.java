package com.lld.workflow;

/**
 * The part of an issue that the audit log can rebuild: where it lives, which state it is in, and how many changes it has had.
 *
 * <p>Free-form fields are not here, because they never change after the issue is created and the log does not store them.
 *
 * @param project the project the issue belongs to
 * @param state the current state name
 * @param version the number of committed changes (0 right after creation)
 * @param workflowVersion which version of the project's workflow the issue follows
 */
public record IssueState(String project, String state, long version, int workflowVersion) {
}
