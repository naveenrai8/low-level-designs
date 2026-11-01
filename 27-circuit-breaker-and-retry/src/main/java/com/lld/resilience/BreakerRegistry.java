package com.lld.resilience;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * One breaker per endpoint, each with its own settings, plus a view of all their metrics.
 *
 * <p>A slow report endpoint and a fast login endpoint should not share one breaker or one threshold. Overrides are fixed when the registry is built (an immutable copy), so there is no moment in which a breaker can be created from the defaults while an override is still being added. Breakers are created on first use and then reused.
 */
public final class BreakerRegistry {

    /**
     * The settings of one endpoint.
     *
     * @param config the thresholds
     * @param countsAsFailure decides which exceptions are the dependency's fault; for example "an HTTP 4xx is not a failure"
     */
    public record Endpoint(CircuitBreakerConfig config, Predicate<Throwable> countsAsFailure) {

        /** Checks that both parts are present. */
        public Endpoint {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(countsAsFailure, "countsAsFailure");
        }

        /** Returns settings where every exception counts as a failure. */
        public static Endpoint of(CircuitBreakerConfig config) {
            return new Endpoint(config, t -> true);
        }
    }

    private final Ticker ticker;
    private final Endpoint defaults;
    private final Map<String, Endpoint> overrides;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /**
     * Creates a registry.
     *
     * @param ticker the clock every breaker shares
     * @param defaults the settings for endpoints without an override
     * @param overrides settings per endpoint name; copied, so later changes to the caller's map have no effect
     */
    public BreakerRegistry(Ticker ticker, Endpoint defaults, Map<String, Endpoint> overrides) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.overrides = Map.copyOf(overrides);
    }

    /** Returns the breaker for the endpoint, creating it on first use. Safe to call from many threads: every caller gets the same instance. */
    public CircuitBreaker get(String endpoint) {
        return breakers.computeIfAbsent(endpoint, e -> {
            Endpoint settings = overrides.getOrDefault(e, defaults);
            return new CircuitBreaker(e, settings.config(), ticker, settings.countsAsFailure());
        });
    }

    /** Returns the metrics of every breaker created so far, sorted by endpoint name. */
    public Map<String, BreakerMetrics> allMetrics() {
        Map<String, BreakerMetrics> out = new TreeMap<>();
        breakers.forEach((k, v) -> out.put(k, v.metrics()));
        return out;
    }
}
