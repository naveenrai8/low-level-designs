package com.lld.flags;

import java.util.Map;

/**
 * Who we are evaluating for: a stable user id plus free-form attributes.
 *
 * @param userId the stable id used for targets, bucketing and sampling; null means anonymous (no target, no rollout, no sampling)
 * @param attributes values like plan or country, copied on construction (String, Boolean or Number values)
 */
public record Context(String userId, Map<String, Object> attributes) {

    /**
     * Copies the attribute map so later changes by the caller cannot change a running evaluation.
     */
    public Context {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Builds a context with just a user id.
     *
     * @param userId the user id
     * @return the context with no attributes
     */
    public static Context of(String userId) {
        return new Context(userId, Map.of());
    }
}
