package com.lld.workflow;

import java.util.function.Predicate;

/**
 * A named yes/no check that must pass before a transition may run.
 *
 * <p>A guard must be pure: no side effects, same answer for the same input. It only receives a {@link GuardContext}, whose issue is an immutable snapshot, so it cannot change anything even by mistake. The name is what the error shows when the guard refuses.
 *
 * @param name a short label shown in the error when this guard fails
 * @param test returns true when the transition may go ahead
 */
public record Guard(String name, Predicate<GuardContext> test) {

    /** Creates a guard that passes only when the issue field {@code key} equals {@code value}. */
    public static Guard fieldEquals(String name, String key, String value) {
        return new Guard(name, ctx -> value.equals(ctx.issue().fields().get(key)));
    }

    /** Creates a guard that passes only when the issue field {@code key} exists and names someone other than the person asking (for example "the reviewer is not the author"). If the field is missing the guard fails: it fails closed. */
    public static Guard actorIsNotField(String name, String key) {
        return new Guard(name, ctx -> {
            String other = ctx.issue().fields().get(key);
            return other != null && !ctx.actor().equals(other);
        });
    }
}
