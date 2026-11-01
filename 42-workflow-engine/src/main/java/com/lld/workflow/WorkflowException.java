package com.lld.workflow;

/**
 * Base class for every error the workflow engine reports on purpose.
 *
 * <p>Each failure kind is a small nested subclass, so a caller can catch exactly the case it cares about (for example only {@link VersionConflict} to retry) and translate it to a status code at the call site.
 */
public class WorkflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the error with a message that names what went wrong. */
    public WorkflowException(String message) {
        super(message);
    }

    /** The workflow definition is not valid (no initial state, unknown state in a transition, unreachable state, duplicate transition name). */
    public static final class InvalidWorkflow extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. The message lists the problems found, not only the first. */
        public InvalidWorkflow(String message) {
            super(message);
        }
    }

    /** The named transition does not exist from the issue's current state. The issue is left unchanged. */
    public static final class IllegalTransition extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error with the issue, the requested transition and the current state in the message. */
        public IllegalTransition(String message) {
            super(message);
        }
    }

    /** A guard said no. The issue is left unchanged. */
    public static final class GuardFailed extends WorkflowException {

        private static final long serialVersionUID = 1L;

        private final String guard;

        /** Creates the error and remembers which guard refused, so the caller can show it to the user. */
        public GuardFailed(String transition, String guard) {
            super("transition '" + transition + "' refused by guard '" + guard + "'");
            this.guard = guard;
        }

        /** Returns the name of the first guard that failed. */
        public String guard() {
            return guard;
        }
    }

    /** The issue changed since the caller read it (its version is not the expected one). The caller should re-read and decide again. */
    public static final class VersionConflict extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public VersionConflict(String message) {
            super(message);
        }
    }

    /** A migration was refused before anything was changed (for example it would leave an issue in a removed state). */
    public static final class MigrationRejected extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. The message lists every issue that blocks the migration. */
        public MigrationRejected(String message) {
            super(message);
        }
    }

    /** A hook chain went deeper than the allowed limit, so the extra transition was refused (this stops hook loops). */
    public static final class HookDepthExceeded extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public HookDepthExceeded(String message) {
            super(message);
        }
    }

    /** The issue or project asked for does not exist. */
    public static final class NotFound extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public NotFound(String message) {
            super(message);
        }
    }

    /** The audit log is not a valid history (a gap in versions, or a step that does not start where the last one ended). */
    public static final class ReplayFailed extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public ReplayFailed(String message) {
            super(message);
        }
    }

    /** A project or issue with this name already exists. */
    public static final class AlreadyExists extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public AlreadyExists(String message) {
            super(message);
        }
    }

    /** The call itself is malformed (for example a null actor). */
    public static final class InvalidRequest extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public InvalidRequest(String message) {
            super(message);
        }
    }

    /** Code running inside the engine's critical section (a guard) tried to call the engine again. This is refused because a nested commit would corrupt versions. */
    public static final class Reentrant extends WorkflowException {

        private static final long serialVersionUID = 1L;

        /** Creates the error. */
        public Reentrant(String message) {
            super(message);
        }
    }
}
