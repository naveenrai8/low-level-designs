package com.lld.flags;

import java.util.List;
import java.util.Map;

/**
 * A targeting rule: if every condition matches the user, serve the given variant.
 *
 * @param id a name unique inside the flag, recorded in evaluation results so you can see which rule fired
 * @param conditions all of these must match (AND); at least one is required
 * @param variant the variant to serve when the rule matches
 */
public record Rule(String id, List<Condition> conditions, String variant) {

    /**
     * Checks the rule is well formed and copies the condition list.
     * <p>Throws {@link InvalidConfigException} for a blank id or variant or an empty condition list.
     */
    public Rule {
        if (id == null || id.isBlank() || variant == null || variant.isBlank()) {
            throw new InvalidConfigException("rule needs an id and a variant");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new InvalidConfigException("rule " + id + " needs at least one condition");
        }
        conditions = List.copyOf(conditions);
    }

    /**
     * Checks all conditions against the user's attributes.
     * <p>The first condition that does not match stops the check. If that was because of a missing attribute or a wrong type, the reason is appended to {@code problems} so the final result can explain itself.
     *
     * @param attributes the user's attributes
     * @param problems a list that receives a note when the rule failed for a missing attribute or type mismatch
     * @return true only when every condition matched
     */
    public boolean matches(Map<String, Object> attributes, List<String> problems) {
        for (Condition c : conditions) {
            Condition.Outcome o = c.test(attributes);
            if (o == Condition.Outcome.MISSING) {
                problems.add("rule " + id + ": missing attribute " + c.attribute());
                return false;
            }
            if (o == Condition.Outcome.TYPE_MISMATCH) {
                problems.add("rule " + id + ": type mismatch on " + c.attribute());
                return false;
            }
            if (o == Condition.Outcome.NO_MATCH) {
                return false;
            }
        }
        return true;
    }
}
