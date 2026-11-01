package com.lld.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.flags.Condition.Op;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the ladder: off, prerequisites, targets, rules, rollout, default. Each level must beat the next.
 */
class PrecedenceTest {
    private static final Rule PRO = new Rule("pro", List.of(new Condition("plan", Op.EQ, List.of("pro"))), "rule");
    private static final Context USER = new Context("vip", Map.of("plan", "pro"));

    /**
     * Builds a flag with every level set to a different variant so the winner is obvious: off=off, target=target, rule=rule, rollout=roll (100%), default=dflt.
     *
     * @param enabled the kill switch
     * @return the flag
     */
    private static Flag.Builder full(boolean enabled) {
        return Flag.builder("f").variants("off", "target", "rule", "roll", "dflt").enabled(enabled).off("off").dflt("dflt")
                .target("vip", "target").rule(PRO).rollout(Rollout.percent("roll", "dflt", 10_000));
    }

    /**
     * Evaluates the flag for the vip user.
     *
     * @param f the flag
     * @return the evaluation
     */
    private static Evaluation run(Flag f) {
        return Fixtures.with(1, f).evaluate("f", USER, "none");
    }

    /**
     * The kill switch beats the target, rule and rollout.
     */
    @Test
    void killSwitchBeatsEverything() {
        Evaluation e = run(full(false).build());
        assertEquals("off", e.variant());
        assertEquals(Reason.OFF, e.reason());
    }

    /**
     * An individual target beats a matching rule.
     */
    @Test
    void targetBeatsRule() {
        Evaluation e = run(full(true).build());
        assertEquals("target", e.variant());
        assertEquals(Reason.TARGET, e.reason());
    }

    /**
     * Without the target, a matching rule beats the rollout.
     */
    @Test
    void ruleBeatsRollout() {
        Flag f = Flag.builder("f").variants("off", "rule", "roll", "dflt").off("off").dflt("dflt").rule(PRO).rollout(Rollout.percent("roll", "dflt", 10_000)).build();
        Evaluation e = run(f);
        assertEquals("rule", e.variant());
        assertEquals(Reason.RULE, e.reason());
    }

    /**
     * Without target or matching rule, the rollout beats the default.
     */
    @Test
    void rolloutBeatsDefault() {
        Flag f = Flag.builder("f").variants("off", "roll", "dflt").off("off").dflt("dflt").rollout(Rollout.percent("roll", "dflt", 10_000)).build();
        Evaluation e = run(f);
        assertEquals("roll", e.variant());
        assertEquals(Reason.ROLLOUT, e.reason());
    }

    /**
     * With nothing else, the default is served; an anonymous user skips the rollout and says so.
     */
    @Test
    void defaultIsTheLastResort() {
        Flag f = Flag.builder("f").variants("off", "roll", "dflt").off("off").dflt("dflt").rollout(Rollout.percent("roll", "dflt", 10_000)).build();
        Evaluation e = Fixtures.with(1, f).evaluate("f", new Context(null, Map.of()), "none");
        assertEquals("dflt", e.variant());
        assertEquals(Reason.DEFAULT, e.reason());
        assertEquals("no user id, rollout skipped", e.detail());
    }

    /**
     * An unknown flag returns the caller's fallback with reason FLAG_NOT_FOUND instead of throwing.
     */
    @Test
    void unknownFlagReturnsFallback() {
        Evaluation e = Fixtures.with(7).evaluate("nope", USER, "fb");
        assertEquals("fb", e.variant());
        assertEquals(Reason.FLAG_NOT_FOUND, e.reason());
        assertEquals(7, e.configVersion());
    }

    /**
     * Builds a two-variant flag that needs another flag to serve "on".
     *
     * @param key the flag key
     * @param needs the prerequisite flag, or null
     * @return the flag
     */
    private static Flag dep(String key, String needs) {
        Flag.Builder b = Flag.builder(key).variants("on", "off").off("off").dflt("on");
        if (needs != null) {
            b.requires(needs, "on");
        }
        return b.build();
    }

    /**
     * B is on only when A serves "on": turning A off turns B off, with reason PREREQUISITE_FAILED.
     */
    @Test
    void prerequisiteGatesTheDependent() {
        Flag aOn = dep("A", null);
        Flag aOff = Flag.builder("A").variants("on", "off").enabled(false).off("off").dflt("on").build();
        Evaluation yes = Fixtures.with(1, aOn, dep("B", "A")).evaluate("B", USER, "x");
        assertEquals("on", yes.variant());
        Evaluation no = Fixtures.with(1, aOff, dep("B", "A")).evaluate("B", USER, "x");
        assertEquals("off", no.variant());
        assertEquals(Reason.PREREQUISITE_FAILED, no.reason());
        assertEquals("needs A=on but it served off (flag is off)", no.detail());
    }

    /**
     * A failed prerequisite beats an individual target on the dependent flag, because it sits above targets in the ladder.
     */
    @Test
    void prerequisiteBeatsTarget() {
        Flag a = Flag.builder("A").variants("on", "off").enabled(false).off("off").dflt("on").build();
        Flag b = Flag.builder("B").variants("on", "off").off("off").dflt("on").requires("A", "on").target("vip", "on").build();
        assertEquals(Reason.PREREQUISITE_FAILED, Fixtures.with(1, a, b).evaluate("B", USER, "x").reason());
    }

    /**
     * Prerequisite cycles (direct and through a third flag) are refused when the config is built.
     */
    @Test
    void cyclesAreRejected() {
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, List.of(dep("A", "B"), dep("B", "A"))));
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, List.of(dep("A", "B"), dep("B", "C"), dep("C", "A"))));
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, List.of(dep("A", "A"))));
    }

    /**
     * A prerequisite on a missing flag or on a variant the other flag does not have is refused.
     */
    @Test
    void danglingPrerequisitesAreRejected() {
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, List.of(dep("A", "ghost"))));
        Flag bad = Flag.builder("A").variants("on", "off").off("off").dflt("on").requires("B", "maybe").build();
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, List.of(bad, dep("B", null))));
    }

    /**
     * A chain of exactly the limit is accepted and one longer is refused.
     */
    @Test
    void depthIsBounded() {
        assertEquals(1, FlagConfig.of(1, chain(FlagConfig.MAX_PREREQ_DEPTH)).version());
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, chain(FlagConfig.MAX_PREREQ_DEPTH + 1)));
    }

    /**
     * Builds flags c0 <- c1 <- ... <- cN where each needs the previous one, so c(N) has depth N.
     *
     * @param n the depth of the last flag
     * @return the flags
     */
    private static List<Flag> chain(int n) {
        List<Flag> out = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            out.add(dep("c" + i, i == 0 ? null : "c" + (i - 1)));
        }
        return out;
    }
}
