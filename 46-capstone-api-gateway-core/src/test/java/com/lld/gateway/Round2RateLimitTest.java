package com.lld.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Round 2 proof: per-client rate limiting with 429 and Retry-After, placed after auth and before the upstream. */
class Round2RateLimitTest {

    /** Proves the 4th request in a window gets 429 with Retry-After in whole seconds, and the upstream is not called for it. */
    @Test
    void fourthRequestIs429WithRetryAfter() {
        Fixture f = new Fixture();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        }
        GatewayResponse r = f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        assertEquals(429, r.status());
        assertEquals("4", r.headers().get("Retry-After"));
        assertEquals(3, f.orders.calls());
    }

    /** Proves waiting the advertised Retry-After really lets the next request through. */
    @Test
    void retryAfterIsHonest() {
        Fixture f = new Fixture();
        for (int i = 0; i < 3; i++) {
            f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        }
        long wait = Long.parseLong(f.gateway.handle(Fixture.get("/orders/1", "key-alice")).headers().get("Retry-After"));
        f.clock.advance(Duration.ofSeconds(wait));
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
    }

    /** Proves one client using up its limit does not affect another client. */
    @Test
    void clientsAreIndependent() {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++) {
            f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        }
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/1", "key-bob")).status());
    }

    /** Proves the limit is per route too, so a busy route does not starve the client's other routes. */
    @Test
    void routesHaveSeparateBuckets() {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++) {
            f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        }
        assertEquals(200, f.gateway.handle(Fixture.get("/health", "key-alice")).status());
    }

    /** Proves requests rejected by auth never spend a token: 10 bad requests leave the full limit for the real client. */
    @Test
    void authRejectionsDoNotConsumeTokens() {
        Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            f.gateway.handle(Fixture.post("/orders", "key-bob"));
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(200, f.gateway.handle(Fixture.get("/orders", "key-bob")).status());
        }
    }

    /** Proves 404 and 405 never spend a token either. */
    @Test
    void routingRejectionsDoNotConsumeTokens() {
        Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            f.gateway.handle(Fixture.get("/nope", "key-alice"));
        }
        assertEquals(200, f.gateway.handle(Fixture.get("/health", "key-alice")).status());
    }

    /** Proves unauthenticated and unauthorised requests keep getting 401 and 403 however many arrive, never 429: auth runs before the limiter, so a flood of bad requests cannot hide behind (or use up) a rate-limit answer. */
    @Test
    void authAnswersAreNeverReplacedByRateLimitAnswers() {
        Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            assertEquals(401, f.gateway.handle(GatewayRequest.of("GET", "/orders/1")).status());
            assertEquals(403, f.gateway.handle(Fixture.get("/billing", "key-bob")).status());
        }
    }

    /** Proves the Retry-After helper keeps whole seconds and rounds any fraction up: 4 s stays 4, 3.333 s becomes 4, 1 ms becomes 1. */
    @Test
    void retryAfterSecondsRoundsUpOnlyFractions() {
        assertEquals(4, Gateway.retryAfterSeconds(Duration.ofSeconds(4)));
        assertEquals(4, Gateway.retryAfterSeconds(Duration.ofMillis(3333)));
        assertEquals(1, Gateway.retryAfterSeconds(Duration.ofMillis(1)));
    }
}
