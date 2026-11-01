package com.lld.flags;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Small helpers shared by the tests so each test reads as one idea.
 */
final class Fixtures {
    /** A frozen clock so event timestamps are exact. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** Not instantiable: this class only holds helpers. */
    private Fixtures() {
    }

    /**
     * Builds a plain on/off flag with the given rollout in basis points.
     *
     * @param key the flag key
     * @param bp the "on" share, 0 to 10,000
     * @return the flag
     */
    static Flag pct(String key, int bp) {
        return Flag.builder(key).variants("on", "off").off("off").dflt("off").rollout(Rollout.percent("on", "off", bp)).build();
    }

    /**
     * Makes an evaluator holding one config version with the given flags and no event sampling.
     *
     * @param version the config version
     * @param flags the flags
     * @return the evaluator
     */
    static InMemoryFeatureFlags with(long version, Flag... flags) {
        InMemoryFeatureFlags f = new InMemoryFeatureFlags(CLOCK, e -> { }, 0);
        f.apply(FlagConfig.of(version, List.of(flags)));
        return f;
    }

    /**
     * Builds a one-attribute context.
     *
     * @param user the user id
     * @param k the attribute name
     * @param v the attribute value
     * @return the context
     */
    static Context ctx(String user, String k, Object v) {
        return new Context(user, Map.of(k, v));
    }
}
