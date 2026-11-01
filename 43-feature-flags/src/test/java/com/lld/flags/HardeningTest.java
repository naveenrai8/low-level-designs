package com.lld.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.flags.Condition.Op;
import com.lld.flags.FeatureFlags.ApplyResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Edge tests added after review: off prerequisites, sampling independence and boundary, a lost apply race, reasons in the detail text, and every build-time check.
 */
class HardeningTest {

    /** A Number subclass whose text is not a number, to prove odd data never makes evaluation throw. */
    private static final class WeirdNumber extends Number {
        private static final long serialVersionUID = 1L;

        /**
         * Returns zero; unused.
         *
         * @return the value
         */
        @Override
        public int intValue() {
            return 0;
        }

        /**
         * Returns zero; unused.
         *
         * @return the value
         */
        @Override
        public long longValue() {
            return 0;
        }

        /**
         * Returns zero; unused.
         *
         * @return the value
         */
        @Override
        public float floatValue() {
            return 0;
        }

        /**
         * Returns zero; unused.
         *
         * @return the value
         */
        @Override
        public double doubleValue() {
            return 0;
        }

        /**
         * Returns text that is not a number, so any code that parses the text would fail.
         *
         * @return the value
         */
        @Override
        public String toString() {
            return "not-a-number";
        }
    }

    /**
     * A prerequisite flag that is switched off counts as unmet even when its off variant is the one required.
     */
    @Test
    void offPrerequisiteIsUnmetEvenIfOffVariantMatches() {
        Flag a = Flag.builder("A").variants("on", "off").enabled(false).off("on").dflt("on").build();
        Flag b = Flag.builder("B").variants("on", "off").off("off").dflt("on").requires("A", "on").build();
        Evaluation e = Fixtures.with(1, a, b).evaluate("B", Context.of("u"), "x");
        assertEquals(Reason.PREREQUISITE_FAILED, e.reason());
        assertEquals("off", e.variant());
    }

    /**
     * Among users inside a 10% rollout, about 10% are sampled at a 10% rate. If sampling reused the rollout bucket it would be 100% (or 0%).
     */
    @Test
    void samplingIsIndependentOfRolloutBucket() {
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, 1000);
        int inRollout = 0;
        int alsoSampled = 0;
        for (int i = 0; i < 100_000; i++) {
            String u = "u" + i;
            if (Bucketing.bucket("f", "s", u) < 1000) {
                inRollout++;
                if (f.sampled("f", "s", u)) {
                    alsoSampled++;
                }
            }
        }
        assertTrue(alsoSampled > inRollout * 0.07 && alsoSampled < inRollout * 0.13, alsoSampled + " of " + inRollout);
    }

    /**
     * A user whose sampling bucket is exactly the rate is out, and one whose bucket is one below is in.
     */
    @Test
    void samplingThresholdIsExclusive() {
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, 1000);
        boolean sawEdge = false;
        boolean sawBelow = false;
        for (int i = 0; i < 400_000 && !(sawEdge && sawBelow); i++) {
            String u = "u" + i;
            int b = Bucketing.bucket("f", "/sample", u);
            if (b == 1000) {
                assertFalse(f.sampled("f", "", u));
                sawEdge = true;
            } else if (b == 999) {
                assertTrue(f.sampled("f", "", u));
                sawBelow = true;
            }
        }
        assertTrue(sawEdge && sawBelow);
    }

    /**
     * The real evaluate path samples using the flag's own salt: a user chosen so the salted and unsalted answers differ must follow the salted one.
     */
    @Test
    void eventPathUsesTheFlagsSalt() {
        BoundedEventBuffer buf = new BoundedEventBuffer(10);
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, buf, 5000);
        f.apply(FlagConfig.of(1, List.of(Flag.builder("f").salt("S").variants("on", "off").off("off").dflt("on").build())));
        String user = null;
        for (int i = 0; user == null; i++) {
            if (Bucketing.bucket("f", "S/sample", "u" + i) < 5000 && Bucketing.bucket("f", "/sample", "u" + i) >= 5000) {
                user = "u" + i;
            }
        }
        f.evaluate("f", Context.of(user), "x");
        assertEquals(1, buf.drain().size());
    }

    /**
     * Another writer wins the race between the read and the compare-and-set. The slower v2 must then see v3 and return STALE, leaving v3 live.
     */
    @Test
    void losingTheApplyRaceReturnsStale() {
        InMemoryFeatureFlags[] box = new InMemoryFeatureFlags[1];
        AtomicInteger calls = new AtomicInteger();
        box[0] = new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, 0, () -> { }, () -> {
            if (calls.incrementAndGet() == 1) {
                box[0].apply(FlagConfig.of(3, List.of()));
            }
        });
        assertEquals(ApplyResult.STALE, box[0].apply(FlagConfig.of(2, List.of())));
        assertEquals(3, box[0].version());
    }

    /**
     * When an earlier rule failed for a missing attribute and a later rule matches, the result still carries the note for the earlier one.
     */
    @Test
    void ruleResultKeepsEarlierNotes() {
        Rule first = new Rule("r1", List.of(new Condition("plan", Op.EQ, List.of("pro"))), "on");
        Rule second = new Rule("r2", List.of(new Condition("age", Op.GTE, List.of(18))), "on");
        Flag f = Flag.builder("f").variants("on", "off").off("off").dflt("off").rule(first).rule(second).build();
        Evaluation e = Fixtures.with(1, f).evaluate("f", Fixtures.ctx("u", "age", 20), "x");
        assertEquals(Reason.RULE, e.reason());
        assertEquals("rule r1: missing attribute plan", e.detail());
    }

    /**
     * When a rule failed for a missing attribute and the rollout decides, the rollout result still carries the note.
     */
    @Test
    void rolloutResultKeepsEarlierNotes() {
        Rule r = new Rule("r1", List.of(new Condition("plan", Op.EQ, List.of("pro"))), "on");
        Flag f = Flag.builder("f").variants("on", "off").off("off").dflt("off").rule(r).rollout(Rollout.percent("on", "off", 10_000)).build();
        Evaluation e = Fixtures.with(1, f).evaluate("f", Context.of("u"), "x");
        assertEquals(Reason.ROLLOUT, e.reason());
        assertEquals("rule r1: missing attribute plan", e.detail());
    }

    /**
     * A sampling rate below 0 or above 10,000 is refused.
     */
    @Test
    void badSampleRateRefused() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, -1));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, 10_001));
    }

    /**
     * An unknown variant in the off slot, a target, a rule or a rollout slice is refused, each on its own.
     */
    @Test
    void unknownVariantRefusedEverywhere() {
        Rule bad = new Rule("r", List.of(new Condition("a", Op.EQ, List.of(1))), "zzz");
        assertThrows(InvalidConfigException.class, () -> Flag.builder("f").variants("on").off("zzz").dflt("on").build());
        assertThrows(InvalidConfigException.class, () -> Flag.builder("f").variants("on").off("on").dflt("on").target("u", "zzz").build());
        assertThrows(InvalidConfigException.class, () -> Flag.builder("f").variants("on").off("on").dflt("on").rule(bad).build());
        assertThrows(InvalidConfigException.class, () -> Flag.builder("f").variants("on").off("on").dflt("on").rollout(Rollout.percent("zzz", "on", 5)).build());
    }

    /**
     * A map key that differs from the flag's own key, and two flags with the same key, are both refused.
     */
    @Test
    void configKeyMismatchAndDuplicatesRefused() {
        Flag y = Fixtures.pct("y", 0);
        assertThrows(InvalidConfigException.class, () -> new FlagConfig(1, Map.of("x", y)));
        assertThrows(InvalidConfigException.class, () -> FlagConfig.of(1, List.of(y, Fixtures.pct("y", 5))));
    }

    /**
     * A negative weight is refused even if another slice would make the total right; weight 0 is fine.
     */
    @Test
    void negativeWeightRefused() {
        assertThrows(InvalidConfigException.class, () -> new Rollout.Slice("a", -1));
        assertEquals(0, new Rollout.Slice("a", 0).weight());
    }

    /**
     * Values of the wrong type are refused when the condition is built, including a non-string for STARTS_WITH, a NaN Float, and a custom Number.
     */
    @Test
    void badConditionValuesRefused() {
        assertThrows(InvalidConfigException.class, () -> new Condition("a", Op.STARTS_WITH, List.of(5)));
        assertThrows(InvalidConfigException.class, () -> new Condition("a", Op.GT, List.of(Float.NaN)));
        assertThrows(InvalidConfigException.class, () -> new Condition("a", Op.GT, List.of(Double.POSITIVE_INFINITY)));
        assertThrows(InvalidConfigException.class, () -> new Condition("a", Op.EQ, List.of(new WeirdNumber())));
    }

    /**
     * A user attribute that is a custom Number does not make evaluation throw; it is a type mismatch.
     */
    @Test
    void customNumberAttributeIsTypeMismatch() {
        Condition c = new Condition("n", Op.GT, List.of(1));
        assertEquals(Condition.Outcome.TYPE_MISMATCH, c.test(Map.of("n", new WeirdNumber())));
        assertEquals(Condition.Outcome.TYPE_MISMATCH, new Condition("n", Op.EQ, List.of(1)).test(Map.of("n", new WeirdNumber())));
    }
}
