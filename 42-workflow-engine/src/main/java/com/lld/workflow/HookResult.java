package com.lld.workflow;

/**
 * What happened when one hook ran for one transition. The engine keeps these so failures are visible instead of silently swallowed.
 *
 * @param hook the hook's registered name
 * @param issueId the issue the hook ran for
 * @param transition the transition that triggered it
 * @param status how it ended
 * @param nanos how long it took, measured with the injected ticker
 * @param error the exception text for a failure, otherwise null
 */
public record HookResult(String hook, String issueId, String transition, Status status, long nanos, String error) {

    /** How a hook run ended. */
    public enum Status {
        /** It finished within its time budget. */
        OK,
        /** It threw an exception. */
        FAILED,
        /** It finished but took longer than the time budget. It cannot be stopped, only reported. */
        SLOW
    }
}
