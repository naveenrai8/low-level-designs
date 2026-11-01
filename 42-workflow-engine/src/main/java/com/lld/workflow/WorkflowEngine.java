package com.lld.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The public contract of the workflow engine: projects with versioned workflows, issues that move through them, hooks and an audit log.
 *
 * <p>Error contract (all are {@link WorkflowException} subclasses, and a failed call changes nothing):
 * <ul>
 * <li>{@code NotFound}: unknown project or issue.
 * <li>{@code VersionConflict}: the expected version is not the issue's current version.
 * <li>{@code IllegalTransition}: no such transition from the current state.
 * <li>{@code GuardFailed}: a guard refused; it names the guard.
 * <li>{@code MigrationRejected}: a migration would strand an issue; nothing was migrated.
 * <li>{@code HookDepthExceeded}: a hook chain went too deep.
 * </ul>
 */
public interface WorkflowEngine {

    /** Pass this as the expected version to mean "do not check the version". */
    long ANY_VERSION = -1;

    /** Registers a project with its first workflow (version 1). Fails if the project already exists. */
    void createProject(String project, WorkflowDefinition definition);

    /**
     * Publishes a new workflow version for the project and moves every issue to it.
     *
     * <p>The mapping says where each old state goes; a state not in the mapping keeps its name. Every issue is checked first, and if any would land in a state the new workflow does not have, it throws {@code MigrationRejected} and nothing changes. Returns the number of issues moved.
     *
     * <p>Issues created while a migration runs wait for it and are then created on the new version, so none is half-moved.
     */
    int migrate(String project, WorkflowDefinition next, Map<String, String> stateMapping, String actor);

    /** Creates an issue in the initial state of the project's latest workflow. Fails if the id is taken. */
    IssueView createIssue(String project, String issueId, Map<String, String> fields, String actor);

    /** Returns the current snapshot of an issue. */
    IssueView get(String issueId);

    /** Returns the transition names allowed from the issue's current state. Guards are not evaluated, so a listed name can still be refused. */
    Set<String> availableTransitions(String issueId);

    /**
     * Runs a transition if the version matches, the move is legal and every guard passes. Returns the new snapshot.
     *
     * <p>Pass {@link #ANY_VERSION} to skip the version check.
     */
    IssueView transition(String issueId, String transitionName, String actor, long expectedVersion);

    /** Registers a named hook that runs after every committed transition. */
    void addHook(String name, Hook hook);

    /** Returns a copy of the append-only audit log, oldest first. */
    List<AuditEntry> auditLog();

    /** Returns a copy of the recorded hook outcomes, in the order they finished. */
    List<HookResult> hookResults();
}
