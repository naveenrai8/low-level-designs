package com.lld.workflow;

/**
 * The only way a hook may change anything: ask for another transition, one level deeper in the chain.
 */
public interface HookActions {

    /**
     * Asks for another transition as the hook, and passes the version the hook saw.
     *
     * <p>If the issue has changed since then (a person got there first), it throws {@link WorkflowException.VersionConflict} and the hook should give up, instead of overwriting the newer change. It throws {@link WorkflowException.HookDepthExceeded} once the chain is deeper than the engine's limit, which is what stops a hook loop.
     */
    IssueView transition(String issueId, String transitionName, long expectedVersion);
}
