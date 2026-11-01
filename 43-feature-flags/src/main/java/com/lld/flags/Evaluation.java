package com.lld.flags;

/**
 * The answer to one evaluation, with enough detail to explain it later.
 *
 * @param flagKey the flag asked about
 * @param variant the variant served
 * @param reason which level of the ladder decided
 * @param ruleId the rule that fired (only when reason is RULE), else null
 * @param configVersion the version of the one snapshot used for this whole evaluation
 * @param detail extra notes such as "rule r1: missing attribute plan"; empty when there is nothing to add
 */
public record Evaluation(String flagKey, String variant, Reason reason, String ruleId, long configVersion, String detail) {
}
