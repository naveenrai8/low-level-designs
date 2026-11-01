package com.lld.workflow;

/**
 * An automation that runs after a transition has committed, for example "when an issue is closed, notify the reporter".
 *
 * <p>A hook can fail or be slow without affecting the transition or other hooks. Here a hook runs at most once per commit: the task is handed to the executor and nothing retries it if the process dies. At-least-once delivery needs a durable outbox (see the README production notes), and then hook effects must be idempotent.
 */
@FunctionalInterface
public interface Hook {

    /** Handles one committed transition. Any exception is caught and recorded as a failed {@link HookResult}. */
    void handle(TransitionEvent event, HookActions actions) throws Exception;
}
