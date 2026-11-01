package com.lld.flags;

import java.time.Instant;

/**
 * A record that one evaluation happened, sent to analytics when the user is sampled.
 *
 * @param at when it happened, from the injected clock
 * @param flagKey the flag
 * @param variant the variant served
 * @param reason why
 * @param ruleId the rule that fired, or null
 * @param configVersion the snapshot version used
 * @param userId the user evaluated
 */
public record EvaluationEvent(Instant at, String flagKey, String variant, Reason reason, String ruleId, long configVersion, String userId) {
}
