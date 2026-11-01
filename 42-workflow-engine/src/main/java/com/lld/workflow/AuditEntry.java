package com.lld.workflow;

import java.time.Instant;

/**
 * One line of the append-only audit log: who changed which issue, when, and from which state to which.
 *
 * <p>Creating an issue is also an entry (transition {@code <create>}, no {@code from}, version 0), so the log alone is enough to rebuild every issue.
 *
 * @param at when it was committed, read from the injected {@code Clock}
 * @param actor who asked for the change
 * @param issueId the issue that changed
 * @param project the issue's project
 * @param transition the transition name, {@code <create>} or {@code <migrate>}
 * @param from the state before, or null for a create
 * @param to the state after
 * @param version the issue's version after this change
 * @param workflowVersion the workflow version the issue follows after this change
 */
public record AuditEntry(Instant at, String actor, String issueId, String project, String transition, String from, String to, long version, int workflowVersion) {

    /** Transition label used for the entry written when an issue is created. */
    public static final String CREATE = "<create>";

    /** Transition label used for the entry written when an issue is moved to a new workflow version. */
    public static final String MIGRATE = "<migrate>";
}
