package com.lld.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Proves preferences, quiet hours, retry and the DLQ, idempotency and per-user rate limiting, all in virtual time. */
class NotificationServiceTest {

    private static final Instant NOON = Instant.parse("2026-01-05T12:00:00Z");

    /** Submits the standard hello to u1 over email with the given key and returns its id. */
    private static String submit(Fixture f, String key) {
        return f.service.submit(key, Fixture.hello("u1", ChannelType.EMAIL)).id();
    }

    /** Proves the happy path: submit sends nothing, runDue sends once and the status becomes SENT. */
    @Test
    void submitThenRunDueSends() {
        Fixture f = new Fixture(NOON, 100, 3);
        String id = submit(f, "k");
        assertEquals(0, f.calls.get());
        assertEquals(Status.PENDING, f.service.find(id).get().status());
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(id).get().status());
        assertEquals(1, f.calls.get());
        f.service.runDue();
        assertEquals(1, f.calls.get());
    }

    /** Proves a user who opted out of a channel gets nothing and the status is SUPPRESSED, even for an urgent message. */
    @Test
    void optOutSuppressesEvenUrgent() {
        Fixture f = new Fixture(NOON, 100, 3);
        f.prefs.put("u1", new UserPreferences(ZoneId.of("UTC"), Map.of(ChannelType.EMAIL, "a", ChannelType.SMS, "b"), Set.of(ChannelType.SMS), Optional.empty()));
        String id = f.service.submit("k", new NotificationRequest("u1", ChannelType.EMAIL, "hello", Map.of("name", "A", "code", "1"), Priority.URGENT)).id();
        f.service.runDue();
        assertEquals(Status.SUPPRESSED, f.service.find(id).get().status());
        assertEquals(0, f.calls.get());
    }

    /** Proves opting out after submit but before the send is honoured, because preferences are read at send time. */
    @Test
    void optOutAfterSubmitIsHonoured() {
        Fixture f = new Fixture(NOON, 100, 3);
        String id = submit(f, "k");
        f.prefs.put("u1", new UserPreferences(ZoneId.of("UTC"), Map.of(ChannelType.EMAIL, "a"), Set.of(), Optional.empty()));
        f.service.runDue();
        assertEquals(Status.SUPPRESSED, f.service.find(id).get().status());
    }

    /** Proves a normal message in quiet hours is held until the end of quiet hours, is not dropped, and goes out exactly then. */
    @Test
    void quietHoursDefersToTheEnd() {
        Fixture f = new Fixture(Instant.parse("2026-01-05T23:00:00Z"), 100, 3);
        f.addUser("u1", ZoneId.of("UTC"), Optional.of(Fixture.night()));
        String id = submit(f, "k");
        f.service.runDue();
        Notification held = f.service.find(id).get();
        assertEquals(Status.PENDING, held.status());
        assertEquals(Instant.parse("2026-01-06T07:00:00Z"), held.nextAttemptAt());
        assertEquals(0, f.calls.get());
        f.clock.set(Instant.parse("2026-01-06T06:59:59Z"));
        f.service.runDue();
        assertEquals(0, f.calls.get());
        f.clock.set(Instant.parse("2026-01-06T07:00:00Z"));
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(id).get().status());
    }

    /** Proves an urgent message ignores quiet hours. */
    @Test
    void urgentBypassesQuietHours() {
        Fixture f = new Fixture(Instant.parse("2026-01-05T23:00:00Z"), 100, 3);
        f.addUser("u1", ZoneId.of("UTC"), Optional.of(Fixture.night()));
        String id = f.service.submit("k", new NotificationRequest("u1", ChannelType.EMAIL, "hello", Map.of("name", "A", "code", "1"), Priority.URGENT)).id();
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(id).get().status());
    }

    /** Proves quiet hours use the user's own zone: a New York user at 23:00 UTC (18:00 local) is not held. */
    @Test
    void quietHoursUseTheUsersZone() {
        Fixture f = new Fixture(Instant.parse("2026-01-05T23:00:00Z"), 100, 3);
        f.addUser("u1", ZoneId.of("America/New_York"), Optional.of(Fixture.night()));
        String id = submit(f, "k");
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(id).get().status());
    }

    /** Proves a message held across the spring-forward night is released at the real end (03:00 local, 07:00Z), not an hour off. */
    @Test
    void heldAcrossSpringForward() {
        Fixture f = new Fixture(Instant.parse("2026-03-08T04:00:00Z"), 100, 3);
        f.addUser("u1", ZoneId.of("America/New_York"), Optional.of(new QuietHours(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(2, 30))));
        String id = submit(f, "k");
        f.service.runDue();
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), f.service.find(id).get().nextAttemptAt());
    }

    /** Proves the retry timeline: two transient failures, then success, with delays inside the jitter ranges and the attempts counted. */
    @Test
    void transientFailuresAreRetriedWithBackoff() {
        Fixture f = new Fixture(NOON, 100, 5);
        f.behaviour = n -> {
            if (n <= 2) {
                throw ChannelException.transientFailure("503");
            }
        };
        String id = submit(f, "k");
        f.service.runDue();
        Notification a = f.service.find(id).get();
        long d1 = Duration.between(NOON, a.nextAttemptAt()).toMillis();
        assertTrue(d1 >= 500 && d1 <= 1000, "first wait " + d1);
        assertEquals(1, a.attempts());
        f.service.runDue();
        assertEquals(1, f.calls.get(), "not due yet, so no second call");
        f.clock.set(a.nextAttemptAt());
        f.service.runDue();
        Notification b = f.service.find(id).get();
        long d2 = Duration.between(a.nextAttemptAt(), b.nextAttemptAt()).toMillis();
        assertTrue(d2 >= 1000 && d2 <= 2000, "second wait " + d2);
        f.clock.set(b.nextAttemptAt());
        f.service.runDue();
        Notification c = f.service.find(id).get();
        assertEquals(Status.SENT, c.status());
        assertEquals(3, c.attempts());
    }

    /** Proves the jitter is seeded: two policies with the same seed give the same waits, and waits grow then stop at the cap. */
    @Test
    void backoffIsDeterministicAndCapped() {
        RetryPolicy a = new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(8), new java.util.Random(7));
        RetryPolicy b = new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(8), new java.util.Random(7));
        for (int i = 1; i <= 40; i++) {
            Duration d = a.delayAfter(i);
            assertEquals(d, b.delayAfter(i));
            assertTrue(d.toMillis() <= 8000 && d.toMillis() >= 500, "attempt " + i + " " + d);
        }
        assertTrue(new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(8), new java.util.Random(1)).delayAfter(40).toMillis() >= 4000);
        Set<Long> firstWaits = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            firstWaits.add(new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(8), new java.util.Random(seed)).delayAfter(1).toMillis());
        }
        assertTrue(firstWaits.size() > 1, "jitter must vary the wait");
    }

    /** Proves a permanent failure goes straight to the DLQ after one attempt with its reason. */
    @Test
    void permanentFailureGoesStraightToDlq() {
        Fixture f = new Fixture(NOON, 100, 5);
        f.behaviour = n -> {
            throw ChannelException.permanentFailure("bad address");
        };
        String id = submit(f, "k");
        f.service.runDue();
        Notification n = f.service.find(id).get();
        assertEquals(Status.DEAD, n.status());
        assertEquals(1, n.attempts());
        assertTrue(n.reason().contains("bad address"));
        assertEquals(List.of(new DeadLetter(id, n.reason(), 1)), f.service.deadLetters());
    }

    /** Proves that after N attempts of transient failure the message is dead with attempts == N and is never sent again. */
    @Test
    void retriesExhaustedGoToDlq() {
        Fixture f = new Fixture(NOON, 100, 3);
        f.behaviour = n -> {
            throw ChannelException.transientFailure("timeout");
        };
        String id = submit(f, "k");
        for (int i = 0; i < 10; i++) {
            f.clock.advance(Duration.ofSeconds(10));
            f.service.runDue();
        }
        Notification n = f.service.find(id).get();
        assertEquals(Status.DEAD, n.status());
        assertEquals(3, n.attempts());
        assertEquals(3, f.calls.get());
        assertTrue(n.reason().contains("retries exhausted"));
    }

    /** Proves the same key and same request returns the same id with replay=true and sends once. */
    @Test
    void sameKeySamePayloadSendsOnce() {
        Fixture f = new Fixture(NOON, 100, 3);
        SubmitResult first = f.service.submit("k", Fixture.hello("u1", ChannelType.EMAIL));
        SubmitResult second = f.service.submit("k", Fixture.hello("u1", ChannelType.EMAIL));
        assertEquals(first.id(), second.id());
        assertFalse(first.replay());
        assertTrue(second.replay());
        f.service.runDue();
        assertEquals(1, f.calls.get());
    }

    /** Proves the same key with a different payload is a conflict and changes nothing. */
    @Test
    void sameKeyDifferentPayloadConflicts() {
        Fixture f = new Fixture(NOON, 100, 3);
        f.service.submit("k", Fixture.hello("u1", ChannelType.EMAIL));
        assertThrows(IdempotencyConflictException.class, () -> f.service.submit("k", Fixture.hello("u1", ChannelType.SMS)));
        assertThrows(IdempotencyConflictException.class, () -> f.service.submit("k", new NotificationRequest("u1", ChannelType.EMAIL, "hello", Map.of("name", "Bob", "code", "42"), Priority.NORMAL)));
        f.service.runDue();
        assertEquals(1, f.calls.get());
    }

    /** Proves 32 threads racing the same key (released together by a start gate) create exactly one notification and one id. */
    @Test
    void concurrentDuplicatesProduceOneSend() throws Exception {
        for (int round = 0; round < 20; round++) {
            Fixture f = new Fixture(NOON, 100, 3);
            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<SubmitResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return f.service.submit("same", Fixture.hello("u1", ChannelType.EMAIL));
                }));
            }
            ready.await();
            go.countDown();
            Set<String> ids = new HashSet<>();
            int created = 0;
            for (Future<SubmitResult> r : results) {
                ids.add(r.get().id());
                if (!r.get().replay()) {
                    created++;
                }
            }
            pool.shutdown();
            assertEquals(1, ids.size());
            assertEquals(1, created);
            assertEquals(1, f.idCounter.get(), "the id supplier ran once");
            f.service.runDue();
            assertEquals(1, f.calls.get());
        }
    }

    /** Proves the notification id is passed to the transport as its idempotency key, so a resend after a crash can be dropped by the provider. */
    @Test
    void transportReceivesTheNotificationIdAsKey() {
        Fixture f = new Fixture(NOON, 100, 3);
        String id = submit(f, "k");
        f.service.runDue();
        assertEquals(List.of(id), f.sentKeys);
    }

    /** Proves the per-user limit defers the extra message (not lost, no attempt used) and it goes out once the limiter has refilled in virtual time. */
    @Test
    void rateLimitDefersInsteadOfDropping() {
        Fixture f = new Fixture(NOON, 2, 3);
        String a = submit(f, "a");
        String b = submit(f, "b");
        String c = submit(f, "c");
        f.service.runDue();
        assertEquals(2, f.calls.get());
        Notification deferred = f.service.find(c).get();
        assertEquals(Status.PENDING, deferred.status());
        assertEquals(0, deferred.attempts());
        assertTrue(deferred.reason().contains("rate limited"));
        assertEquals(Duration.ofSeconds(30), Duration.between(NOON, deferred.nextAttemptAt()));
        f.clock.set(deferred.nextAttemptAt().minusMillis(1));
        f.service.runDue();
        assertEquals(2, f.calls.get());
        f.clock.set(deferred.nextAttemptAt());
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(c).get().status());
        assertEquals(Status.SENT, f.service.find(a).get().status());
        assertEquals(Status.SENT, f.service.find(b).get().status());
    }

    /** Proves the limit is per user: one busy user does not slow another. */
    @Test
    void rateLimitIsPerUser() {
        Fixture f = new Fixture(NOON, 1, 3);
        f.addUser("u2", ZoneId.of("UTC"), Optional.empty());
        submit(f, "a");
        String other = f.service.submit("b", Fixture.hello("u2", ChannelType.EMAIL)).id();
        String again = submit(f, "c");
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(other).get().status());
        assertEquals(Status.PENDING, f.service.find(again).get().status());
    }

    /** Proves an unknown id is empty rather than an error. */
    @Test
    void unknownIdIsEmpty() {
        assertTrue(new Fixture(NOON, 1, 1).service.find("nope").isEmpty());
    }

    /** Proves attempts never exceed maxAttempts even when the transport always fails and runDue is called many times. */
    @Test
    void attemptsNeverExceedTheLimit() {
        Fixture f = new Fixture(NOON, 100, 2);
        AtomicInteger seen = new AtomicInteger();
        f.behaviour = n -> {
            seen.incrementAndGet();
            throw ChannelException.transientFailure("x");
        };
        submit(f, "k");
        for (int i = 0; i < 20; i++) {
            f.clock.advance(Duration.ofMinutes(1));
            f.service.runDue();
        }
        assertEquals(2, seen.get());
    }
}
