package com.lld.jiralite;

/** Base of the errors this module adds on top of the workflow engine's own errors. */
public class JiraException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the error with a message. */
    public JiraException(String message) {
        super(message);
    }

    /** The person can see the project but lacks the right for this action. */
    public static final class PermissionDenied extends JiraException {
        private static final long serialVersionUID = 1L;

        /** Creates the error with a message. */
        public PermissionDenied(String message) {
            super(message);
        }
    }

    /** No such issue, or the person may not browse its project. The two cases look the same on purpose, so nobody can probe which keys exist. */
    public static final class IssueNotFound extends JiraException {
        private static final long serialVersionUID = 1L;

        /** Creates the error for the given key; the text never says why. */
        public IssueNotFound(String issueId) {
            super("no such issue: " + issueId);
        }
    }

    /** The project has permission rows but was never created. Thrown only after the permission check passed, so it cannot be used to probe project names. */
    public static final class ProjectNotFound extends JiraException {
        private static final long serialVersionUID = 1L;

        /** Creates the error for the given project key. */
        public ProjectNotFound(String project) {
            super("no such project: " + project);
        }
    }

    /** The search index could not be brought up to date, so search refuses to answer rather than answer with stale data. The message is generic on purpose: the hidden issue key and the internal cause are only in {@code getCause()}, which the caller layer must not show to users. */
    public static final class IndexUnavailable extends JiraException {
        private static final long serialVersionUID = 1L;

        /** Creates the error with a generic message and the real cause. */
        public IndexUnavailable(String message, Throwable cause) {
            super(message);
            initCause(cause);
        }
    }
}
