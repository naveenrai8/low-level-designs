package com.lld.gateway;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimiterFactory;
import com.lld.resilience.BreakerRegistry;
import com.lld.resilience.CircuitBreaker;
import com.lld.resilience.CircuitOpenException;
import com.lld.resilience.CircuitState;
import com.lld.resilience.Retry;
import com.lld.resilience.RetryBudget;
import com.lld.resilience.Sleeper;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The gateway pipeline. One request goes through these steps in this order, and every step can end the request early:
 *
 * <ol>
 * <li>body size check (413) and path cleaning (400)
 * <li>route lookup (404 / 405)
 * <li>auth chain (401 / 403)
 * <li>rate limit per client and route (429 + Retry-After)
 * <li>retry loop (only for safe requests), bounded by the request deadline
 * <li>circuit breaker per upstream (503 when open)
 * <li>the upstream call, with a timeout that fits the remaining deadline (502 / 504)
 * </ol>
 *
 * <p>The order is the point: the cheapest and most certain rejections come first, and a request rejected early never reaches a later step, so it cannot spend a limit token, count against a breaker, or touch the upstream.
 *
 * <p>Concurrency model: the gateway is shared by all request threads. Its only shared mutable state lives inside the Topic 21 limiter (one atomic update per key), the Topic 27 breakers (one lock each) and the retry budgets (one lock each, created through a concurrent map). The gateway itself keeps no per-request state in fields: the deadline, the last failure and the random source are locals of one call, so two requests cannot see each other.
 */
public final class Gateway {

    private static final Set<String> SAFE_TO_RETRY = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    private final RouteTable routes;
    private final AuthChain auth;
    private final Map<String, Upstream> upstreams;
    private final GatewayConfig config;
    private final Ticker ticker;
    private final Sleeper sleeper;
    private final Supplier<RandomGenerator> random;
    private final KeyedRateLimiter<?> limiter;
    private final BreakerRegistry breakers;
    private final Map<String, RetryBudget> budgets = new ConcurrentHashMap<>();

    /**
     * Wires the pipeline. Fails at startup, not at request time, if a route names an upstream that does not exist.
     *
     * <p>The one {@code ticker} feeds the limiter, the breakers and the deadline, so tests move them all together. {@code random} is a supplier because a shared random generator is not safe to use from many threads: production passes {@code ThreadLocalRandom::current}, tests pass a seeded one.
     */
    public Gateway(RouteTable routes, AuthChain auth, Map<String, Upstream> upstreams, GatewayConfig config, Ticker ticker, Sleeper sleeper, Supplier<RandomGenerator> random) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.upstreams = Map.copyOf(upstreams);
        this.config = Objects.requireNonNull(config, "config");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
        for (Route r : routes.routes()) {
            if (!this.upstreams.containsKey(r.upstream())) {
                throw new IllegalArgumentException("route " + r.name() + " names an unknown upstream: " + r.upstream());
            }
        }
        this.limiter = RateLimiterFactory.create(config.algorithm(), config.limit(), ticker.forRateLimiter());
        this.breakers = new BreakerRegistry(ticker.forResilience(), new BreakerRegistry.Endpoint(config.breaker(), t -> t instanceof UpstreamException), Map.of());
    }

    /** Runs one request through the whole pipeline and returns the response to send. Never throws for an expected failure: every failure is a status code. */
    public GatewayResponse handle(GatewayRequest raw) {
        if (raw.body().length() > config.maxBodyBytes()) {
            return GatewayResponse.of(413, "body too large");
        }
        GatewayRequest request;
        try {
            request = raw.withPath(PathNormalizer.normalize(raw.path()));
        } catch (IllegalArgumentException e) {
            return GatewayResponse.of(400, "bad path");
        }
        RouteTable.Lookup found = routes.lookup(request.method(), request.path());
        if (found.result() == RouteTable.Result.NOT_FOUND) {
            return GatewayResponse.of(404, "no route");
        }
        if (found.result() == RouteTable.Result.METHOD_NOT_ALLOWED) {
            return GatewayResponse.of(405, "method not allowed").withHeader("Allow", String.join(", ", found.allowed()));
        }
        Route route = found.route();
        GatewayRequest routed = request.withPathParams(found.params());

        AuthDecision who = auth.run(routed, route);
        if (who.rejected()) {
            return who.rejection();
        }
        if (who.principal() == null) {
            return GatewayResponse.of(401, "unauthorized").withHeader("WWW-Authenticate", "ApiKey");
        }

        Decision d = limiter.tryAcquire(who.principal().id() + "|" + route.name(), 1);
        if (!d.allowed()) {
            return GatewayResponse.of(429, "rate limited").withHeader("Retry-After", Long.toString(retryAfterSeconds(d.retryAfter())));
        }

        // The credential stays at the gateway. The backend gets the identity we decided, never one the client claimed.
        GatewayRequest outgoing = routed.without("x-api-key").without("x-client-id").withHeader("X-Client-Id", who.principal().id());
        return forward(outgoing, route);
    }

    /** Calls the upstream with the retry loop (if the request is safe to repeat) and turns every failure into a status code. */
    private GatewayResponse forward(GatewayRequest request, Route route) {
        long deadline = ticker.read() + config.deadline().toNanos();
        Upstream upstream = upstreams.get(route.upstream());
        CircuitBreaker breaker = breakers.get(route.upstream());
        RetryBudget budget = budgets.computeIfAbsent(route.upstream(), n -> new RetryBudget(config.retryRatioPermille(), config.retryMaxTokens()));
        AtomicReference<RuntimeException> lastFailure = new AtomicReference<>();
        try {
            if (!retryAllowed(request) || config.maxAttempts() == 1) {
                // Retry.call would count this request toward the budget; here we do it by hand so the budget is a share of ALL forwarded traffic.
                budget.onRequest();
                return attempt(request, upstream, breaker, deadline, lastFailure);
            }
            // A refused retry still spent a budget token: Topic 27's Retry takes the token before it sleeps.
            Sleeper boundedSleeper = delay -> {
                if (ticker.read() + delay.toNanos() >= deadline) {
                    throw new DeadlineExceededException();
                }
                sleeper.sleep(delay);
            };
            Retry retry = new Retry(config.maxAttempts(), config.backoffBase(), config.backoffCap(), random.get(), boundedSleeper, budget, Gateway::worthRetrying);
            return retry.call(() -> attempt(request, upstream, breaker, deadline, lastFailure));
        } catch (CircuitOpenException e) {
            RuntimeException earlier = lastFailure.get();
            return earlier != null ? failureResponse(earlier) : openCircuitResponse();
        } catch (DeadlineExceededException e) {
            RuntimeException earlier = lastFailure.get();
            return earlier != null ? failureResponse(earlier) : GatewayResponse.of(504, "deadline exceeded");
        } catch (UpstreamException e) {
            return failureResponse(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GatewayResponse.of(503, "interrupted");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            return GatewayResponse.of(502, "bad gateway");
        }
    }

    /** Turns an upstream failure into the response the client should see: the backend's own answer for a 5xx, 504 for a timeout, 502 for anything else. */
    private static GatewayResponse failureResponse(RuntimeException e) {
        if (e instanceof UpstreamFailureException f) {
            return f.response();
        }
        if (e instanceof UpstreamTimeoutException) {
            return GatewayResponse.of(504, "upstream timeout");
        }
        return GatewayResponse.of(502, "bad gateway");
    }

    /** The fast-fail answer of an open breaker: 503 with {@code Retry-After} set to the breaker's open wait, rounded up to whole seconds. It is an upper bound: the breaker may already have been open for a while. */
    private GatewayResponse openCircuitResponse() {
        long seconds = retryAfterSeconds(Duration.ofNanos(config.breaker().waitInOpenNanos()));
        return GatewayResponse.of(503, "circuit open").withHeader("Retry-After", Long.toString(seconds));
    }

    /**
     * One try: ask the breaker for permission, call the upstream with a timeout that fits what is left.
     *
     * <p>The deadline is checked here as well as before each retry wait, because a real sleep can overshoot: a wait that was fine when it started may end after the deadline, and a zero or negative timeout would mean "wait forever" for some HTTP clients. A 5xx answer is thrown so the breaker records a failure; every failure is remembered so a refused retry can still answer with the last thing the backend said.
     */
    private GatewayResponse attempt(GatewayRequest request, Upstream upstream, CircuitBreaker breaker, long deadline, AtomicReference<RuntimeException> lastFailure) throws Exception {
        long left = deadline - ticker.read();
        if (left <= 0) {
            throw new DeadlineExceededException();
        }
        Duration timeout = Duration.ofNanos(Math.min(left, config.perTryTimeout().toNanos()));
        try {
            return breaker.call(() -> {
                GatewayResponse r = upstream.handle(request, timeout);
                if (r.isServerError()) {
                    throw new UpstreamFailureException(r);
                }
                return r;
            });
        } catch (UpstreamException e) {
            lastFailure.set(e);
            throw e;
        }
    }

    /** True when repeating this request cannot do harm: GET, HEAD, OPTIONS, PUT and DELETE are idempotent by definition (RFC 9110 section 9.2.2); POST and PATCH only when the client sent a non-blank {@code Idempotency-Key} the backend can dedupe on. */
    static boolean retryAllowed(GatewayRequest request) {
        String key = request.header("idempotency-key");
        return SAFE_TO_RETRY.contains(request.method()) || (key != null && !key.isBlank());
    }

    /** Decides whether a failure is worth another try: the backend was unreachable, timed out, or said 502/503/504. A plain 500 is not retried, because it is usually a bug that will fail again. */
    static boolean worthRetrying(Throwable t) {
        if (t instanceof UpstreamFailureException f) {
            int s = f.response().status();
            return s == 502 || s == 503 || s == 504;
        }
        return t instanceof UpstreamException;
    }

    /** Rounds a wait up to whole seconds for the {@code Retry-After} header (RFC 9110 section 10.2.3). Rounding down would send the client back before its permit exists. */
    static long retryAfterSeconds(Duration wait) {
        long seconds = wait.toSeconds();
        return wait.equals(Duration.ofSeconds(seconds)) ? seconds : seconds + 1;
    }

    /** Returns the state of one upstream's breaker, for dashboards and tests. */
    public CircuitState breakerState(String upstream) {
        return breakers.get(upstream).state();
    }

    /** Returns the breaker's counters for one upstream. */
    public com.lld.resilience.BreakerMetrics breakerMetrics(String upstream) {
        return breakers.get(upstream).metrics();
    }
}
