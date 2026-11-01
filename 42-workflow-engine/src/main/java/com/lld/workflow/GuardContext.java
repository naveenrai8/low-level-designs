package com.lld.workflow;

/**
 * Everything a guard may look at: a read-only snapshot of the issue and the name of the person asking.
 *
 * @param issue the issue as it is right now (immutable)
 * @param actor who is asking for the transition
 */
public record GuardContext(IssueView issue, String actor) {
}
