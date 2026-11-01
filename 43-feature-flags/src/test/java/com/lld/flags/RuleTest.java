package com.lld.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.flags.Condition.Op;
import com.lld.flags.Condition.Outcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves rule order, first-match-wins, fail-closed on missing attributes, and type handling.
 */
class RuleTest {

    /**
     * Builds a condition with one value.
     *
     * @param attr the attribute
     * @param op the operator
     * @param v the value
     * @return the condition
     */
    private static Condition c(String attr, Op op, Object v) {
        return new Condition(attr, op, List.of(v));
    }

    /**
     * Two rules both match; the one listed first wins, and swapping the order swaps the winner.
     */
    @Test
    void firstMatchingRuleWins() {
        Rule pro = new Rule("pro", List.of(c("plan", Op.EQ, "pro")), "B");
        Rule any = new Rule("any", List.of(c("age", Op.GTE, 18)), "C");
        Flag.Builder b = Flag.builder("f").variants("A", "B", "C").off("A").dflt("A");
        InMemoryFeatureFlags first = Fixtures.with(1, b.rule(pro).rule(any).build());
        Context u = new Context("u", Map.of("plan", "pro", "age", 30));
        Evaluation e = first.evaluate("f", u, "A");
        assertEquals("B", e.variant());
        assertEquals("pro", e.ruleId());
        assertEquals(Reason.RULE, e.reason());
        InMemoryFeatureFlags swapped = Fixtures.with(1, Flag.builder("f").variants("A", "B", "C").off("A").dflt("A").rule(any).rule(pro).build());
        assertEquals("C", swapped.evaluate("f", u, "A").variant());
    }

    /**
     * A missing attribute does not match, the flag falls to the default, and the reason text names the attribute.
     */
    @Test
    void missingAttributeFailsClosedWithReason() {
        Rule r = new Rule("r1", List.of(c("plan", Op.EQ, "pro")), "B");
        InMemoryFeatureFlags f = Fixtures.with(1, Flag.builder("f").variants("A", "B").off("A").dflt("A").rule(r).build());
        Evaluation e = f.evaluate("f", Context.of("u"), "A");
        assertEquals("A", e.variant());
        assertEquals(Reason.DEFAULT, e.reason());
        assertEquals("rule r1: missing attribute plan", e.detail());
    }

    /**
     * A string where a number is needed (and a number where a string is needed) never matches and says type mismatch.
     */
    @Test
    void typeMismatchIsNotAMatch() {
        assertEquals(Outcome.TYPE_MISMATCH, c("age", Op.GTE, 18).test(Map.of("age", "30")));
        assertEquals(Outcome.TYPE_MISMATCH, c("plan", Op.EQ, "5").test(Map.of("plan", 5)));
        assertEquals(Outcome.TYPE_MISMATCH, c("plan", Op.STARTS_WITH, "p").test(Map.of("plan", 5)));
        assertEquals(Outcome.TYPE_MISMATCH, c("age", Op.GT, 1).test(Map.of("age", Double.NaN)));
        assertEquals(Outcome.TYPE_MISMATCH, c("x", Op.NEQ, "a").test(Map.of("x", true)));
        Rule r = new Rule("r1", List.of(c("age", Op.GTE, 18)), "B");
        InMemoryFeatureFlags f = Fixtures.with(1, Flag.builder("f").variants("A", "B").off("A").dflt("A").rule(r).build());
        Evaluation e = f.evaluate("f", Fixtures.ctx("u", "age", "30"), "A");
        assertEquals("A", e.variant());
        assertEquals("rule r1: type mismatch on age", e.detail());
    }

    /**
     * Each operator gives the right answer on both sides of its boundary; numbers compare by value so 5 equals 5.0 and a long keeps full precision.
     */
    @Test
    void operatorsAtTheirBoundaries() {
        assertEquals(Outcome.MATCH, c("n", Op.GTE, 18).test(Map.of("n", 18)));
        assertEquals(Outcome.NO_MATCH, c("n", Op.GT, 18).test(Map.of("n", 18)));
        assertEquals(Outcome.MATCH, c("n", Op.LT, 18).test(Map.of("n", 17)));
        assertEquals(Outcome.NO_MATCH, c("n", Op.LT, 18).test(Map.of("n", 18)));
        assertEquals(Outcome.MATCH, c("n", Op.LTE, 18).test(Map.of("n", 18.0)));
        assertEquals(Outcome.MATCH, c("n", Op.EQ, 5).test(Map.of("n", 5.0)));
        assertEquals(Outcome.NO_MATCH, c("n", Op.GT, 9007199254740992L).test(Map.of("n", 9007199254740992L)));
        assertEquals(Outcome.MATCH, c("n", Op.GT, 9007199254740992L).test(Map.of("n", 9007199254740993L)));
        assertEquals(Outcome.MATCH, c("p", Op.STARTS_WITH, "pr").test(Map.of("p", "pro")));
        assertEquals(Outcome.MATCH, c("p", Op.NEQ, "pro").test(Map.of("p", "free")));
        assertEquals(Outcome.NO_MATCH, c("p", Op.NEQ, "pro").test(Map.of("p", "pro")));
        Condition in = new Condition("p", Op.IN, List.of("pro", "team"));
        assertEquals(Outcome.MATCH, in.test(Map.of("p", "team")));
        assertEquals(Outcome.NO_MATCH, in.test(Map.of("p", "free")));
        assertEquals(Outcome.MATCH, c("b", Op.EQ, true).test(Map.of("b", true)));
    }

    /**
     * All conditions in a rule must match (AND): one failing condition fails the rule.
     */
    @Test
    void allConditionsMustMatch() {
        Rule r = new Rule("r", List.of(c("plan", Op.EQ, "pro"), c("age", Op.GTE, 18)), "B");
        InMemoryFeatureFlags f = Fixtures.with(1, Flag.builder("f").variants("A", "B").off("A").dflt("A").rule(r).build());
        assertEquals("B", f.evaluate("f", new Context("u", Map.of("plan", "pro", "age", 18)), "A").variant());
        assertEquals("A", f.evaluate("f", new Context("u", Map.of("plan", "pro", "age", 17)), "A").variant());
    }

    /**
     * Badly shaped rules, conditions and flags are refused when built, not when evaluated.
     */
    @Test
    void badShapesAreRefused() {
        assertThrows(InvalidConfigException.class, () -> new Condition("a", Op.GT, List.of("x")));
        assertThrows(InvalidConfigException.class, () -> new Condition("a", Op.EQ, List.of("x", "y")));
        assertThrows(InvalidConfigException.class, () -> new Condition("", Op.EQ, List.of("x")));
        assertThrows(InvalidConfigException.class, () -> new Rule("r", List.of(), "A"));
        assertThrows(InvalidConfigException.class, () -> Flag.builder("f").variants("A").off("A").dflt("Z").build());
        Rule r = new Rule("r", List.of(c("a", Op.EQ, 1)), "A");
        assertThrows(InvalidConfigException.class, () -> Flag.builder("f").variants("A").off("A").dflt("A").rule(r).rule(r).build());
    }
}
