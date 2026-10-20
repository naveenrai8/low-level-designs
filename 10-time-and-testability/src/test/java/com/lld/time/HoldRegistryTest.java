package com.lld.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HoldRegistryTest {

    private static final Instant T0 = Instant.parse("2026-03-08T09:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(T0);
    private final ManualScheduler scheduler = new ManualScheduler(clock);

    // ---- Boundary: half-open [created, expiresAt) ----------------------------------------------

    @Test
    void fixHoldIsActiveOneNanoBeforeExpiryAndGoneAtExactlyExpiry() {
        HoldRegistry holds = new ExpiringHoldRegistry(clock, scheduler, TTL);
        assertTrue(holds.tryHold("7A", "alice").isPresent());

        clock.advance(TTL.minusNanos(1));               // move the clock only; the sweep has NOT run
        assertTrue(holds.activeHold("7A").isPresent());
        assertTrue(holds.tryHold("7A", "bob").isEmpty());

        clock.advance(Duration.ofNanos(1));             // exactly expiresAt
        assertTrue(holds.activeHold("7A").isEmpty(), "the read path decides, without waiting for the sweep");
        assertEquals("bob", holds.tryHold("7A", "bob").orElseThrow().user());
    }

    @Test
    void bugInclusiveExpiryStillBlocksTheSeatAtExactlyExpiry() {
        HoldRegistry holds = new InclusiveExpiryHoldRegistry(clock, scheduler, TTL);
        holds.tryHold("7A", "alice");

        clock.advance(TTL);                             // exactly expiresAt
        assertTrue(holds.activeHold("7A").isPresent(), "bug: alice still holds at the instant her hold ends");
        assertTrue(holds.tryHold("7A", "bob").isEmpty(), "bug: bob is turned away from a free seat");
    }

    // ---- Stale timers: a timer must remove only the hold it was created for ---------------------

    @Test
    void bugStaleTimerDeletesTheNextUsersHold() {
        HoldRegistry holds = new StaleTimerHoldRegistry(clock, scheduler, TTL);
        Hold alice = holds.tryHold("7A", "alice").orElseThrow();       // timer due at T0+10m
        scheduler.advance(Duration.ofMinutes(1));
        holds.release(alice);                                           // alice cancels early
        scheduler.advance(Duration.ofMinutes(1));
        Hold bob = holds.tryHold("7A", "bob").orElseThrow();           // bob's hold runs to T0+12m

        scheduler.advance(Duration.ofMinutes(8));                       // T0+10m: alice's timer fires
        assertTrue(clock.instant().isBefore(bob.expiresAt()));
        assertTrue(holds.activeHold("7A").isEmpty(), "bug: alice's stale timer removed bob's hold");
        assertTrue(holds.tryHold("7A", "carol").isPresent(), "bug: the seat bob is paying for is resold");
    }

    @Test
    void fixStaleTimerLeavesTheNextUsersHoldAlone() {
        HoldRegistry holds = new ExpiringHoldRegistry(clock, scheduler, TTL);
        Hold alice = holds.tryHold("7A", "alice").orElseThrow();
        scheduler.advance(Duration.ofMinutes(1));
        holds.release(alice);
        scheduler.advance(Duration.ofMinutes(1));
        Hold bob = holds.tryHold("7A", "bob").orElseThrow();

        scheduler.advance(Duration.ofMinutes(8));                       // alice's timer fires: no-op
        assertEquals(Optional.of(bob), holds.activeHold("7A"));

        scheduler.advance(Duration.ofMinutes(2));                       // T0+12m: bob's own timer
        assertTrue(holds.activeHold("7A").isEmpty());
        assertEquals(0, holds.size());
    }

    // ---- Memory: the sweep keeps the map bounded -----------------------------------------------

    @Test
    void fixExpiredHoldsAreSweptSoMemoryStaysBounded() {
        HoldRegistry holds = new ExpiringHoldRegistry(clock, scheduler, TTL);
        for (int i = 0; i < 1_000; i++) {
            holds.tryHold("seat-" + i, "user-" + i);
            scheduler.advance(Duration.ofMillis(1));
        }
        assertEquals(1_000, holds.size());

        scheduler.advance(TTL);                          // ten virtual minutes, run in microseconds
        assertEquals(0, holds.size());
        assertEquals(0, scheduler.pending());
    }

    // ---- Concurrency: exactly one winner per seat ------------------------------------------------

    @Test
    void fixExactlyOneOfManyConcurrentUsersWinsTheSeat() throws Exception {
        // ManualScheduler is single-threaded, so this test stubs the scheduler out: it is about tryHold only.
        HoldRegistry holds = new ExpiringHoldRegistry(clock, (delay, task) -> () -> false, TTL);
        int threads = 32;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String user = "user-" + i;
                results.add(pool.submit(() -> {
                    startGate.await();
                    return holds.tryHold("7A", user).isPresent();
                }));
            }
            startGate.countDown();
            int winners = 0;
            for (Future<Boolean> r : results) {
                if (r.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertFalse(holds.activeHold("7A").isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }
}
