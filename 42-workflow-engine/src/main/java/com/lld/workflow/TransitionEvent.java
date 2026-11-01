package com.lld.workflow;

/**
 * What a hook is told after a transition has committed.
 *
 * @param issue the issue as it is after the change
 * @param transition the transition name that ran
 * @param from the state before
 * @param actor who asked for it
 * @param depth 0 when a person triggered it, +1 for each hook-triggered transition in the chain
 */
public record TransitionEvent(IssueView issue, String transition, String from, String actor, int depth) {
}
