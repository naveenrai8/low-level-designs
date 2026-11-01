package com.lld.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves per-endpoint config, per-endpoint failure rules, the metrics view and that get-or-create is race free. */
class BreakerRegistryTest {

    private final FakeTicker ticker = new FakeTicker();
    private static final Duration W = Duration.ofSeconds(30);

    /** A sensitive endpoint trips after two failures while an endpoint on the default settings does not, and the metrics show both. */
    @Test
    void endpointsHaveIndependentSettingsAndMetrics() {
        BreakerRegistry reg = new BreakerRegistry(ticker, BreakerRegistry.Endpoint.of(CircuitBreakerConfig.countBased(50, 4, 4, W, 1)),
            Map.of("payments", BreakerRegistry.Endpoint.of(CircuitBreakerConfig.countBased(50, 2, 2, W, 1))));
        Support.tryOnce(reg.get("payments"), false);
        Support.tryOnce(reg.get("payments"), false);
        Support.tryOnce(reg.get("search"), false);
        Support.tryOnce(reg.get("search"), false);
        assertEquals(CircuitState.OPEN, reg.get("payments").state());
        assertEquals(CircuitState.CLOSED, reg.get("search").state());
        assertEquals(2, reg.allMetrics().size());
        assertEquals(2, reg.allMetrics().get("payments").failures());
        assertEquals("payments", reg.allMetrics().keySet().iterator().next());
    }

    /** One endpoint can say "an IllegalArgumentException (think HTTP 4xx) is not a failure" without changing the others. */
    @Test
    void perEndpointFailurePredicate() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.countBased(50, 2, 2, W, 1);
        BreakerRegistry reg = new BreakerRegistry(ticker, BreakerRegistry.Endpoint.of(cfg),
            Map.of("lenient", new BreakerRegistry.Endpoint(cfg, t -> !(t instanceof IllegalArgumentException))));
        for (String name : new String[] {"lenient", "strict"}) {
            for (int i = 0; i < 2; i++) {
                assertThrows(IllegalArgumentException.class, () -> reg.get(name).call(() -> {
                    throw new IllegalArgumentException("4xx");
                }));
            }
        }
        assertEquals(CircuitState.CLOSED, reg.get("lenient").state());
        assertEquals(CircuitState.OPEN, reg.get("strict").state());
    }

    /** The same endpoint always gets the same breaker, and changing the caller's map afterwards has no effect. */
    @Test
    void breakersAreReusedAndOverridesAreCopied() {
        Map<String, BreakerRegistry.Endpoint> mine = new HashMap<>();
        BreakerRegistry reg = new BreakerRegistry(ticker, BreakerRegistry.Endpoint.of(CircuitBreakerConfig.countBased(50, 4, 4, W, 1)), mine);
        mine.put("a", BreakerRegistry.Endpoint.of(CircuitBreakerConfig.countBased(50, 2, 2, W, 1)));
        assertSame(reg.get("a"), reg.get("a"));
        assertNotSame(reg.get("a"), reg.get("b"));
        Support.tryOnce(reg.get("a"), false);
        Support.tryOnce(reg.get("a"), false);
        assertEquals(CircuitState.CLOSED, reg.get("a").state());
    }

    /** Many threads asking for one endpoint at the same instant all receive one single breaker instance. */
    @Test
    @Timeout(20)
    void concurrentGetCreatesExactlyOneBreaker() throws InterruptedException {
        BreakerRegistry reg = new BreakerRegistry(ticker, BreakerRegistry.Endpoint.of(CircuitBreakerConfig.countBased(50, 2, 2, W, 1)), Map.of());
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<CircuitBreaker> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    CircuitBreaker cb = reg.get("x");
                    synchronized (seen) {
                        seen.add(cb);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(1, seen.size());
        assertEquals(1, reg.allMetrics().size());
    }

    /** Missing pieces are refused at construction. */
    @Test
    void nullsAreRefused() {
        BreakerRegistry.Endpoint d = BreakerRegistry.Endpoint.of(CircuitBreakerConfig.countBased(50, 2, 2, W, 1));
        assertThrows(NullPointerException.class, () -> new BreakerRegistry(null, d, Map.of()));
        assertThrows(NullPointerException.class, () -> new BreakerRegistry(ticker, null, Map.of()));
        assertThrows(NullPointerException.class, () -> new BreakerRegistry.Endpoint(null, t -> true));
    }
}
