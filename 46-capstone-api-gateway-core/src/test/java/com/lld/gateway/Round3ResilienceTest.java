package com.lld.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.resilience.CircuitState;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Round 3 proof: breaker per upstream, safe retries, retry budget and the request deadline. */
class Round3ResilienceTest {

    /** Proves a GET that fails with 503 once is retried and the client sees the later 200. */
    @Test
    void getIsRetriedAfter503() {
        Fixture f = new Fixture();
        f.orders.statuses(503, 200);
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        assertEquals(2, f.orders.calls());
    }

    /** Proves a POST without an Idempotency-Key is never retried, because a repeat could create two orders. */
    @Test
    void postWithoutKeyIsNotRetried() {
        Fixture f = new Fixture();
        f.orders.statuses(503, 200);
        assertEquals(503, f.gateway.handle(Fixture.post("/orders", "key-alice")).status());
        assertEquals(1, f.orders.calls());
    }

    /** Proves a POST with an Idempotency-Key is retried, because the backend can dedupe on the key. */
    @Test
    void postWithKeyIsRetried() {
        Fixture f = new Fixture();
        f.orders.statuses(503, 200);
        assertEquals(200, f.gateway.handle(Fixture.post("/orders", "key-alice").withHeader("Idempotency-Key", "k1")).status());
        assertEquals(2, f.orders.calls());
    }

    /** Proves PUT counts as idempotent and is retried. */
    @Test
    void putIsRetried() {
        Fixture f = new Fixture();
        f.orders.statuses(502, 200);
        GatewayRequest put = GatewayRequest.of("PUT", "/orders").withHeader("X-API-Key", "key-alice");
        assertEquals(200, f.gateway.handle(put).status());
    }

    /** Proves a plain 500 is not retried (likely a bug that repeats) yet the client still sees the backend's 500. */
    @Test
    void status500IsNotRetried() {
        Fixture f = new Fixture();
        f.orders.always(500);
        assertEquals(500, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        assertEquals(1, f.orders.calls());
    }

    /** Proves retries stop at maxAttempts when the budget has saved enough tokens: one earlier good request saves a token, so the failing request gets its 2 retries (3 calls). */
    @Test
    void retriesStopAtMaxAttempts() {
        Fixture f = new Fixture(Fixture.config(3, 1000, Duration.ofSeconds(5), 8));
        f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        f.orders.always(503);
        assertEquals(503, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        assertEquals(1 + 3, f.orders.calls());
    }

    /** Proves the retry budget caps retries at the ratio: at 100% a fresh client earns one token per request, so 3 allowed attempts become 2 real calls. */
    @Test
    void retryBudgetCapsRetriesBelowMaxAttempts() {
        Fixture f = new Fixture(Fixture.config(3, 1000, Duration.ofSeconds(5), 8));
        f.orders.always(503);
        assertEquals(503, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        assertEquals(2, f.orders.calls());
    }

    /** Proves the breaker opens in the middle of the retry story: after 2 failures it is OPEN and the client still gets the backend's 503. */
    @Test
    void breakerOpensAfterRetriedFailures() {
        Fixture f = new Fixture();
        f.orders.always(503);
        assertEquals(503, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        assertEquals(2, f.orders.calls());
        assertEquals(CircuitState.OPEN, f.gateway.breakerState("orders"));
    }

    /** Proves a 4xx from the backend is passed through, not retried, and not counted against the breaker. */
    @Test
    void clientErrorsAreNotRetriedOrCounted() {
        Fixture f = new Fixture();
        f.orders.always(404);
        for (int i = 0; i < 3; i++) {
            assertEquals(404, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        }
        assertEquals(3, f.orders.calls());
        assertEquals(0, f.gateway.breakerMetrics("orders").failures());
        assertEquals(CircuitState.CLOSED, f.gateway.breakerState("orders"));
    }

    /** Proves an empty retry budget switches retries off, so a failing backend sees no extra load. */
    @Test
    void emptyRetryBudgetStopsRetries() {
        Fixture f = new Fixture(Fixture.config(3, 0, Duration.ofSeconds(5)));
        f.orders.always(503);
        f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        assertEquals(1, f.orders.calls());
    }

    /** Proves the breaker opens after enough failures and then answers 503 without calling the upstream. */
    @Test
    void breakerOpensAndFailsFast() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.billing.always(500);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        assertEquals(CircuitState.OPEN, f.gateway.breakerState("billing"));
        int before = f.billing.calls();
        GatewayResponse r = f.gateway.handle(Fixture.get("/billing", "key-erin"));
        assertEquals(503, r.status());
        assertEquals("circuit open", r.body());
        assertEquals("30", r.headers().get("Retry-After"));
        assertEquals(before, f.billing.calls());
    }

    /** Proves an open breaker is per upstream: orders keep working while billing is open. */
    @Test
    void breakersArePerUpstream() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.billing.always(500);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
    }

    /** Proves after the open wait one probe goes through, and a good probe closes the breaker. */
    @Test
    void halfOpenProbeSuccessCloses() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.billing.always(500);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.billing.always(200);
        f.clock.advance(Duration.ofSeconds(30));
        assertEquals(CircuitState.HALF_OPEN, f.gateway.breakerState("billing"));
        assertEquals(200, f.gateway.handle(Fixture.get("/billing", "key-erin")).status());
        assertEquals(CircuitState.CLOSED, f.gateway.breakerState("billing"));
    }

    /** Proves a failed probe reopens the breaker. */
    @Test
    void halfOpenProbeFailureReopens() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.billing.always(500);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.clock.advance(Duration.ofSeconds(30));
        assertEquals(500, f.gateway.handle(Fixture.get("/billing", "key-erin")).status());
        assertEquals(CircuitState.OPEN, f.gateway.breakerState("billing"));
    }

    /** Proves requests rejected by the limiter are not breaker failures: 20 rejections leave the breaker closed with zero failures. */
    @Test
    void limiterRejectionsDoNotCountAsBreakerFailures() {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) {
            f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        }
        assertEquals(0, f.gateway.breakerMetrics("orders").failures());
        assertEquals(CircuitState.CLOSED, f.gateway.breakerState("orders"));
    }

    /** Proves auth rejections never reach the breaker at all. */
    @Test
    void authRejectionsNeverReachTheBreaker() {
        Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            f.gateway.handle(GatewayRequest.of("GET", "/orders/1"));
        }
        assertEquals(0, f.gateway.breakerMetrics("orders").successes() + f.gateway.breakerMetrics("orders").failures());
    }

    /** Proves retry waits count against the deadline: a 1 s deadline with always-timing-out upstream stops early with 504 and far fewer than maxAttempts calls. */
    @Test
    void deadlineBoundsRetries() {
        Fixture f = new Fixture(Fixture.config(10, 1000, Duration.ofSeconds(1), 50));
        for (int i = 0; i < 8; i++) {
            f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
            f.clock.advance(Duration.ofSeconds(10));
        }
        int warm = f.orders.calls();
        f.orders.slowTimeouts(Duration.ofMillis(400));
        GatewayResponse r = f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        assertEquals(504, r.status());
        int used = f.orders.calls() - warm;
        assertTrue(used < 10, "calls: " + used);
        assertTrue(used >= 2, "calls: " + used);
    }

    /** Proves each upstream call gets a timeout no bigger than the time left before the deadline. */
    @Test
    void perCallTimeoutShrinksToRemainingDeadline() {
        Fixture f = new Fixture(Fixture.config(3, 1000, Duration.ofMillis(1500)));
        f.orders.slowTimeouts(Duration.ofMillis(700));
        f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        assertEquals(Duration.ofSeconds(1).plusMillis(500), f.orders.timeouts().get(0));
        assertTrue(f.orders.timeouts().get(1).compareTo(Duration.ofMillis(800)) <= 0, "second timeout: " + f.orders.timeouts().get(1));
    }

    /** Proves an upstream timeout that survives all attempts becomes 504 and an unreachable upstream becomes 502. */
    @Test
    void timeoutIs504AndUnreachableIs502() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.orders.alwaysThrows(new UpstreamTimeoutException("t"));
        assertEquals(504, f.gateway.handle(Fixture.get("/orders", "key-alice")).status());
        f.orders.alwaysThrows(new UpstreamException("down"));
        assertEquals(502, f.gateway.handle(Fixture.get("/orders", "key-bob")).status());
    }

    /** Proves an open breaker is not retried: the retry loop gives up at once on the fast-fail. */
    @Test
    void openBreakerIsNotRetried() {
        Fixture f = new Fixture(Fixture.config(5, 1000, Duration.ofSeconds(5)));
        f.billing.always(503);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        int calls = f.billing.calls();
        f.clock.advance(Duration.ofSeconds(1));
        assertEquals(503, f.gateway.handle(Fixture.get("/billing", "key-erin")).status());
        assertEquals(calls, f.billing.calls());
    }

    /** A gateway with one route per method on /r (no scope) to the orders upstream, a breaker that needs 50 calls to open, and the given retry settings. */
    private static Fixture methodsFixture(int maxAttempts, int ratioPermille) {
        List<Route> routes = List.of(new Route("r", Set.of("GET", "PUT", "DELETE", "OPTIONS", "PATCH", "POST"), "/r", "orders", null));
        return new Fixture(Fixture.config(maxAttempts, ratioPermille, Duration.ofSeconds(5), 50), routes);
    }

    /** Proves PATCH without an Idempotency-Key is not retried: one call, and the client sees the 503. */
    @Test
    void patchWithoutKeyIsNotRetried() {
        Fixture f = methodsFixture(3, 1000);
        f.orders.statuses(503, 200);
        assertEquals(503, f.gateway.handle(GatewayRequest.of("PATCH", "/r").withHeader("X-API-Key", "key-alice")).status());
        assertEquals(1, f.orders.calls());
    }

    /** Proves every method that is idempotent by definition is retried once: PUT, DELETE, HEAD, OPTIONS and GET each end in 200 after 2 calls. */
    @Test
    void idempotentMethodsAreRetried() {
        for (String method : new String[] {"PUT", "DELETE", "HEAD", "OPTIONS", "GET"}) {
            Fixture f = methodsFixture(3, 1000);
            f.orders.statuses(503, 200);
            assertEquals(200, f.gateway.handle(GatewayRequest.of(method, "/r").withHeader("X-API-Key", "key-alice")).status(), method);
            assertEquals(2, f.orders.calls(), method);
        }
    }

    /** Proves a blank Idempotency-Key does not make a POST retryable. */
    @Test
    void blankIdempotencyKeyDoesNotEnableRetries() {
        Fixture f = methodsFixture(3, 1000);
        f.orders.statuses(503, 200);
        assertEquals(503, f.gateway.handle(GatewayRequest.of("POST", "/r").withHeader("X-API-Key", "key-alice").withHeader("Idempotency-Key", "  ")).status());
        assertEquals(1, f.orders.calls());
    }

    /** Proves 502, 503 and 504 answers are each retried (a 504 answer included). */
    @Test
    void gatewayErrorStatusesAreRetried() {
        for (int status : new int[] {502, 503, 504}) {
            Fixture f = methodsFixture(3, 1000);
            f.orders.statuses(status, 200);
            assertEquals(200, f.gateway.handle(Fixture.get("/r", "key-alice")).status(), "status " + status);
            assertEquals(2, f.orders.calls(), "status " + status);
        }
    }

    /** Proves an unreachable backend (not just a timeout) is retried. */
    @Test
    void unreachableBackendIsRetried() {
        Fixture f = methodsFixture(3, 1000);
        f.orders.sequence(new UpstreamException("refused"), 200);
        assertEquals(200, f.gateway.handle(Fixture.get("/r", "key-alice")).status());
        assertEquals(2, f.orders.calls());
    }

    /** Proves a timeout is retried too, and the client sees the later 200. */
    @Test
    void timeoutIsRetried() {
        Fixture f = methodsFixture(3, 1000);
        f.orders.sequence(new UpstreamTimeoutException("slow"), 200);
        assertEquals(200, f.gateway.handle(Fixture.get("/r", "key-alice")).status());
    }

    /** Proves timeouts and unreachable errors count as breaker failures: two of them open the breaker. */
    @Test
    void timeoutsAndUnreachableCountAsBreakerFailures() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.orders.alwaysThrows(new UpstreamTimeoutException("slow"));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        f.orders.alwaysThrows(new UpstreamException("refused"));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        assertEquals(CircuitState.OPEN, f.gateway.breakerState("orders"));
    }

    /** Proves the first upstream call gets the per-try timeout when the deadline is longer: 5 s deadline, 2 s per try, so exactly 2 s. */
    @Test
    void firstCallGetsThePerTryTimeoutWhenDeadlineIsLonger() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        assertEquals(Duration.ofSeconds(2), f.orders.timeouts().get(0));
    }

    /** Proves a retry that would end after the deadline is refused before it sleeps: 960 ms used of a 1 s deadline, the wait (50 ms or more) does not fit, so there is exactly 1 call and virtual time moved only by the call itself. */
    @Test
    void retryThatCannotFitBeforeTheDeadlineIsNotStarted() {
        Fixture f = new Fixture(Fixture.config(2, 1000, Duration.ofSeconds(1), 50));
        f.orders.slowTimeouts(Duration.ofMillis(960));
        long start = f.clock.now();
        GatewayResponse r = f.gateway.handle(Fixture.get("/orders", "key-alice"));
        assertEquals(504, r.status());
        assertEquals(1, f.orders.calls());
        assertEquals(start + Duration.ofMillis(960).toNanos(), f.clock.now());
    }

    /** Proves a sleep that ends late (real sleeps do) cannot start an attempt past the deadline: the late retry is not called, and the client sees the backend's last 503 instead of a made-up 504. */
    @Test
    void lateSleepDoesNotStartAnAttemptPastTheDeadline() {
        Fixture f = new Fixture(Fixture.config(3, 1000, Duration.ofSeconds(1), 50));
        f.clock.overshoot(Duration.ofSeconds(2));
        f.orders.always(503);
        GatewayResponse r = f.gateway.handle(Fixture.get("/orders", "key-alice"));
        assertEquals(503, r.status());
        assertEquals("s503", r.body());
        assertEquals(1, f.orders.calls());
    }

    /** Proves the retry budget is per upstream, not per route: requests on one route earn tokens that a retry on another route of the same upstream can spend. At 500 permille each request earns half a token. */
    @Test
    void retryBudgetIsSharedByRoutesOfOneUpstream() {
        Fixture f = new Fixture(Fixture.config(3, 500, Duration.ofSeconds(5), 50));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        f.orders.statuses(503, 200);
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        assertEquals(4, f.orders.calls());
    }

    /** Proves requests that are never retried still earn budget tokens, so the budget is a share of ALL forwarded traffic: two POSTs earn one token, which a later GET spends on its retry. */
    @Test
    void nonRetryableRequestsStillFeedTheBudget() {
        Fixture f = new Fixture(Fixture.config(3, 500, Duration.ofSeconds(5), 50));
        f.gateway.handle(Fixture.post("/orders", "key-alice"));
        f.gateway.handle(Fixture.post("/orders", "key-alice"));
        f.orders.statuses(503, 200);
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
    }

    /** Proves the breaker opening INSIDE a retry loop stops the loop: with a saved-up budget the third attempt is refused by the open breaker, the upstream sees 2 calls, and the client gets the backend's own last 503 (not a 504). */
    @Test
    void breakerOpeningInsideTheLoopStopsTheLoop() {
        Fixture f = new Fixture(Fixture.config(3, 1000, Duration.ofSeconds(5), 2));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        f.gateway.handle(Fixture.get("/orders", "key-alice"));
        int before = f.orders.calls();
        f.orders.always(503);
        GatewayResponse r = f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        assertEquals(503, r.status());
        assertEquals("s503", r.body());
        assertEquals(2, f.orders.calls() - before);
        assertEquals(CircuitState.OPEN, f.gateway.breakerState("orders"));
        assertEquals(1, f.gateway.breakerMetrics("orders").rejected());
    }

    /** Proves a request that finds the breaker already open (nothing was tried) gets the fast 503 "circuit open" with a Retry-After. */
    @Test
    void alreadyOpenBreakerGivesCircuitOpenWithRetryAfter() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        f.billing.always(500);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        GatewayResponse r = f.gateway.handle(Fixture.get("/billing", "key-erin"));
        assertEquals("circuit open", r.body());
        assertEquals("30", r.headers().get("Retry-After"));
    }
}
