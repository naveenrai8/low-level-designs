package com.lld.gateway;

import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import com.lld.resilience.CircuitBreakerConfig;
import com.lld.resilience.Sleeper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Shared test setup: virtual time, a scripted upstream, a standard route table and a ready-made gateway. */
final class Fixture {

    /** Virtual time. Sleeping moves it forward instantly, so tests never wait. */
    static final class Clock {
        private final AtomicLong nanos = new AtomicLong(1_000);
        private Duration overshoot = Duration.ZERO;

        /** Returns the current virtual time as a gateway ticker. */
        Ticker ticker() {
            return nanos::get;
        }

        /** Returns a sleeper that moves virtual time forward by the requested delay PLUS the current overshoot, like a real sleep that ends late. */
        Sleeper sleeper() {
            return d -> advance(d.plus(overshoot));
        }

        /** Makes every later sleep end this much later than asked. */
        void overshoot(Duration extra) {
            overshoot = extra;
        }

        /** Returns the virtual time now, in nanoseconds. */
        long now() {
            return nanos.get();
        }

        /** Moves virtual time forward. */
        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    /** A fake backend that plays back a script of behaviours, one per call, and repeats the last one forever. */
    static final class ScriptedUpstream implements Upstream {
        private final List<Step> script = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private final List<GatewayRequest> seen = new ArrayList<>();
        private final Clock clock;
        private int calls;
        private int position;

        /** One scripted behaviour: take some virtual time, then answer or throw. */
        record Step(Duration latency, GatewayResponse response, RuntimeException failure) {
        }

        /** Creates an upstream that answers 200 until told otherwise. */
        ScriptedUpstream(Clock clock) {
            this.clock = clock;
            script.add(new Step(Duration.ZERO, GatewayResponse.of(200, "ok"), null));
        }

        /** Replaces the script with one repeating answer. */
        ScriptedUpstream always(int status) {
            script.clear();
            position = 0;
            script.add(new Step(Duration.ZERO, GatewayResponse.of(status, "s" + status), null));
            return this;
        }

        /** Replaces the script with one repeating exception. */
        ScriptedUpstream alwaysThrows(RuntimeException e) {
            script.clear();
            position = 0;
            script.add(new Step(Duration.ZERO, null, e));
            return this;
        }

        /** Replaces the script with a list of statuses; the last repeats. */
        ScriptedUpstream statuses(int... statuses) {
            script.clear();
            position = 0;
            for (int s : statuses) {
                script.add(new Step(Duration.ZERO, GatewayResponse.of(s, "s" + s), null));
            }
            return this;
        }

        /** Replaces the script with a mixed sequence: an Integer is a status to answer, a RuntimeException is thrown. The last item repeats. */
        ScriptedUpstream sequence(Object... items) {
            script.clear();
            position = 0;
            for (Object o : items) {
                if (o instanceof Integer status) {
                    script.add(new Step(Duration.ZERO, GatewayResponse.of(status, "s" + status), null));
                } else {
                    script.add(new Step(Duration.ZERO, null, (RuntimeException) o));
                }
            }
            return this;
        }

        /** Makes every call take this much virtual time before it throws a timeout. */
        ScriptedUpstream slowTimeouts(Duration latency) {
            script.clear();
            position = 0;
            script.add(new Step(latency, null, new UpstreamTimeoutException("slow")));
            return this;
        }

        /** Answers the next scripted step. The position restarts at the first step whenever the script is replaced, and the last step repeats. */
        @Override
        public GatewayResponse handle(GatewayRequest request, Duration timeout) {
            Step step = script.get(Math.min(position, script.size() - 1));
            position++;
            calls++;
            timeouts.add(timeout);
            seen.add(request);
            clock.advance(step.latency());
            if (step.failure() != null) {
                throw step.failure();
            }
            return step.response();
        }

        /** How many times the upstream was called. */
        int calls() {
            return calls;
        }

        /** The timeout the gateway passed on each call. */
        List<Duration> timeouts() {
            return timeouts;
        }

        /** The requests the upstream received. */
        List<GatewayRequest> seen() {
            return seen;
        }
    }

    final Clock clock = new Clock();
    final ScriptedUpstream orders = new ScriptedUpstream(clock);
    final ScriptedUpstream billing = new ScriptedUpstream(clock);
    final Gateway gateway;

    /** The standard clients: alice can read and write orders, bob can only read orders, erin can only read billing. */
    static final Map<String, Principal> KEYS = Map.of(
            "key-alice", new Principal("alice", Set.of("orders:read", "orders:write")),
            "key-bob", new Principal("bob", Set.of("orders:read")),
            "key-erin", new Principal("erin", Set.of("billing:read")));

    /** Builds the gateway with the default config. */
    Fixture() {
        this(config(3, 1000, Duration.ofSeconds(5)));
    }

    /** Builds the gateway with a custom config. */
    Fixture(GatewayConfig config) {
        this(config, STANDARD_ROUTES);
    }

    /** The routes most tests use. */
    static final List<Route> STANDARD_ROUTES = List.of(
            new Route("order-get", Set.of("GET"), "/orders/{id}", "orders", "orders:read"),
            new Route("order-list", Set.of("GET"), "/orders", "orders", "orders:read"),
            new Route("order-create", Set.of("POST", "PUT"), "/orders", "orders", "orders:write"),
            new Route("billing", Set.of("GET", "POST"), "/billing", "billing", "billing:read"),
            new Route("health", Set.of("GET"), "/health", "orders", null));

    /** Builds the gateway with a custom config and a custom route list. */
    Fixture(GatewayConfig config, List<Route> routes) {
        gateway = new Gateway(new RouteTable(routes), new AuthChain(List.of(new ApiKeyAuthHook(KEYS), new ScopeAuthHook())),
                Map.of("orders", orders, "billing", billing), config, clock.ticker(), clock.sleeper(), fixedRandom());
    }

    /** The default config: 3 requests per 10 s per client and route, a breaker that opens at 50% failures after 2 calls, 30 s open wait, 1 probe. */
    static GatewayConfig config(int maxAttempts, int retryRatioPermille, Duration deadline) {
        return config(maxAttempts, retryRatioPermille, deadline, 2);
    }

    /** Like the default config but with a chosen minimum number of calls before the breaker may open. */
    static GatewayConfig config(int maxAttempts, int retryRatioPermille, Duration deadline, int breakerMinCalls) {
        return new GatewayConfig(RateLimiterFactory.Algorithm.TOKEN_BUCKET, new RateLimit(3, Duration.ofSeconds(10)),
                CircuitBreakerConfig.countBased(50, breakerMinCalls, Math.max(10, breakerMinCalls), Duration.ofSeconds(30), 1), maxAttempts, Duration.ofMillis(100), Duration.ofSeconds(1),
                retryRatioPermille, 10, deadline, Duration.ofSeconds(2), 1024);
    }

    /** A seeded random source, so jitter is the same on every run. Each request gets its own copy, as it would with a per-thread generator in production. */
    private static Supplier<RandomGenerator> fixedRandom() {
        return () -> new java.util.SplittableRandom(1);
    }

    /** Builds a GET request carrying the given API key. */
    static GatewayRequest get(String path, String key) {
        return GatewayRequest.of("GET", path).withHeader("X-API-Key", key);
    }

    /** Builds a POST request carrying the given API key. */
    static GatewayRequest post(String path, String key) {
        return GatewayRequest.of("POST", path).withHeader("X-API-Key", key);
    }
}
