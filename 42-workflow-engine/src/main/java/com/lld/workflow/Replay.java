package com.lld.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds every issue's state from an audit log, starting from nothing.
 *
 * <p>Replay is a pure function of the log: it never touches guards, hooks or the clock. That is deliberate. Hooks have side effects in the outside world (emails, webhooks) that already happened once, so replaying must not fire them again. Guards were already checked when the entry was first committed, so replay only trusts the log and checks that it is a consistent chain.
 */
public final class Replay {

    /** Not instantiable: only the static {@link #rebuild(List)} is used. */
    private Replay() {
    }

    /**
     * Applies the entries in order and returns the final state of each issue, keyed by issue id.
     *
     * <p>It throws {@link WorkflowException.ReplayFailed} if an entry does not follow from the one before it: an unknown issue, a {@code from} that is not the current state, or a version that is not exactly one more.
     */
    public static Map<String, IssueState> rebuild(List<AuditEntry> log) {
        Map<String, IssueState> out = new HashMap<>();
        for (AuditEntry e : log) {
            IssueState cur = out.get(e.issueId());
            if (AuditEntry.CREATE.equals(e.transition())) {
                if (cur != null || e.version() != 0) {
                    throw new WorkflowException.ReplayFailed("bad create entry for " + e.issueId());
                }
            } else {
                if (cur == null) {
                    throw new WorkflowException.ReplayFailed("entry for unknown issue " + e.issueId());
                }
                if (!cur.state().equals(e.from())) {
                    throw new WorkflowException.ReplayFailed("issue " + e.issueId() + " is in '" + cur.state() + "' but the entry starts from '" + e.from() + "'");
                }
                if (e.version() != cur.version() + 1) {
                    throw new WorkflowException.ReplayFailed("issue " + e.issueId() + " jumps from version " + cur.version() + " to " + e.version());
                }
            }
            out.put(e.issueId(), new IssueState(e.project(), e.to(), e.version(), e.workflowVersion()));
        }
        return out;
    }
}
