package com.lld.notify;

/**
 * The answer to a submit.
 *
 * @param id the notification id (the same id for every repeat of one idempotency key)
 * @param replay true if this key was already used with the same request, so nothing new was created
 */
public record SubmitResult(String id, boolean replay) {
}
