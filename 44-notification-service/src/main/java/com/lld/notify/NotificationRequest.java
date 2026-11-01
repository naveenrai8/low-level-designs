package com.lld.notify;

import java.util.Map;
import java.util.Objects;

/**
 * What a caller asks for: "send template X to user U over channel C with these variables".
 *
 * <p>Two requests are the same payload when all fields are equal (it is a record). That is how an idempotency key is checked: same key + equal request means a repeat, same key + different request means a conflict.
 *
 * @param userId who receives it
 * @param channel which medium
 * @param template the template name
 * @param variables the values for the template placeholders (copied, so the caller cannot change them later; null keys or values are rejected)
 * @param priority normal, or urgent to skip quiet hours
 */
public record NotificationRequest(String userId, ChannelType channel, String template, Map<String, String> variables, Priority priority) {

    /** Checks no field is null or blank, no variable is null, and copies the variables so the record is truly immutable. */
    public NotificationRequest {
        if (userId == null || userId.isBlank()) {
            throw new InvalidRequestException("userId is required");
        }
        if (template == null || template.isBlank()) {
            throw new InvalidRequestException("template is required");
        }
        if (channel == null) {
            throw new InvalidRequestException("channel is required");
        }
        if (variables != null && hasNull(variables)) {
            // Map.copyOf would throw a NullPointerException (a 500 over HTTP); a null variable is the caller's mistake, so it is a bad request.
            throw new InvalidRequestException("template variables must not contain null keys or values");
        }
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        priority = Objects.requireNonNullElse(priority, Priority.NORMAL);
    }

    /** Looks at every entry instead of asking {@code containsKey(null)}, because immutable maps such as {@code Map.of()} throw a NullPointerException when asked about null. */
    private static boolean hasNull(Map<String, String> m) {
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                return true;
            }
        }
        return false;
    }
}
