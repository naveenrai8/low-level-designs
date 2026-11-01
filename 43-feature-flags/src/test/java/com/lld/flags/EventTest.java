package com.lld.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.flags.Condition.Op;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves evaluation events: right fields, deterministic sampling, bounded buffer with exact drop counts, and a broken sink cannot break evaluation.
 */
class EventTest {

    /**
     * Makes a rule-based flag so events carry a rule id.
     *
     * @return the flag
     */
    private static Flag flag() {
        Rule r = new Rule("r1", List.of(new Condition("plan", Op.EQ, List.of("pro"))), "on");
        return Flag.builder("f").variants("on", "off").off("off").dflt("off").rule(r).build();
    }

    /**
     * One event carries the flag, variant, reason, rule id, config version, user id and the injected clock time.
     */
    @Test
    void eventCarriesAllFields() {
        BoundedEventBuffer buf = new BoundedEventBuffer(10);
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, buf, 10_000);
        f.apply(FlagConfig.of(3, List.of(flag())));
        f.evaluate("f", Fixtures.ctx("u1", "plan", "pro"), "x");
        EvaluationEvent e = buf.drain().get(0);
        assertEquals(new EvaluationEvent(Fixtures.CLOCK.instant(), "f", "on", Reason.RULE, "r1", 3, "u1"), e);
    }

    /**
     * Sampling is a function of the user: asking twice about each of 20,000 users always gives the same answer, and about 10% of users are in.
     */
    @Test
    void samplingIsPerUserAndStable() {
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, 1000);
        Set<String> in = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            boolean first = f.sampled("f", "", "u" + i);
            assertEquals(first, f.sampled("f", "", "u" + i));
            if (first) {
                in.add("u" + i);
            }
        }
        assertTrue(in.size() > 1800 && in.size() < 2200, "sampled " + in.size());
    }

    /**
     * Through the real path: a sampled user produces an event on every call, an unsampled user on none.
     */
    @Test
    void sampledUserAlwaysEmitsOthersNever() {
        BoundedEventBuffer buf = new BoundedEventBuffer(100_000);
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, buf, 5000);
        f.apply(FlagConfig.of(1, List.of(flag())));
        String s0 = null;
        String unsampled = null;
        for (int i = 0; s0 == null || unsampled == null; i++) {
            if (f.sampled("f", "", "u" + i)) {
                s0 = "u" + i;
            } else {
                unsampled = "u" + i;
            }
        }
        final String sampled = s0;
        for (int i = 0; i < 5; i++) {
            f.evaluate("f", Context.of(sampled), "x");
            f.evaluate("f", Context.of(unsampled), "x");
        }
        List<EvaluationEvent> events = buf.drain();
        assertEquals(5, events.size());
        assertTrue(events.stream().allMatch(e -> e.userId().equals(sampled)));
    }

    /**
     * The two ends of sampling: 0 reports nothing, 10,000 reports everything including anonymous users.
     */
    @Test
    void sampleExtremes() {
        BoundedEventBuffer none = new BoundedEventBuffer(10);
        BoundedEventBuffer all = new BoundedEventBuffer(10);
        InMemoryFeatureFlags f0 = new InMemoryFeatureFlags(Fixtures.CLOCK, none, 0);
        InMemoryFeatureFlags f1 = new InMemoryFeatureFlags(Fixtures.CLOCK, all, 10_000);
        f0.apply(FlagConfig.of(1, List.of(flag())));
        f1.apply(FlagConfig.of(1, List.of(flag())));
        f0.evaluate("f", Context.of("u"), "x");
        f1.evaluate("f", new Context(null, null), "x");
        assertEquals(0, none.drain().size());
        assertEquals(1, all.drain().size());
    }

    /**
     * A buffer of 3 that gets 10 events keeps the first 3 and counts exactly 7 drops; after draining there is room again.
     */
    @Test
    void fullBufferDropsAndCountsExactly() {
        BoundedEventBuffer buf = new BoundedEventBuffer(3);
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, buf, 10_000);
        f.apply(FlagConfig.of(1, List.of(flag())));
        for (int i = 0; i < 10; i++) {
            f.evaluate("f", Context.of("u" + i), "x");
        }
        assertEquals(7, buf.dropped());
        List<EvaluationEvent> kept = buf.drain();
        assertEquals(List.of("u0", "u1", "u2"), kept.stream().map(EvaluationEvent::userId).toList());
        f.evaluate("f", Context.of("u10"), "x");
        assertEquals(7, buf.dropped());
        assertEquals(1, buf.drain().size());
    }

    /**
     * A sink that throws is counted and swallowed: the caller still gets the right answer.
     */
    @Test
    void brokenSinkDoesNotBreakEvaluation() {
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(Fixtures.CLOCK, e -> {
            throw new IllegalStateException("analytics down");
        }, 10_000);
        f.apply(FlagConfig.of(1, List.of(flag())));
        Evaluation e = f.evaluate("f", Fixtures.ctx("u", "plan", "pro"), "x");
        assertEquals("on", e.variant());
        assertEquals(1, f.sinkFailures());
    }
}
