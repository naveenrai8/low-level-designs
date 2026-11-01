package com.lld.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves the edge cases found in review: transport crashes, missing addresses, ordering, null inputs, replay, and the small constructor checks. */
class ReviewFixesTest {

    private static final Instant NOON = Instant.parse("2026-01-05T12:00:00Z");

    /** Proves a transport that throws an unexpected exception counts attempts, ends DEAD, and does not block another user's message in the same runs. */
    @Test
    void crashingTransportDoesNotBlockOthers() {
        Fixture f = new Fixture(NOON, 100, 3);
        f.prefs.put("u2", new UserPreferences(ZoneId.of("UTC"), Map.of(ChannelType.EMAIL, "u2@x.com"), Set.of(ChannelType.EMAIL), Optional.empty()));
        f.byAddress = a -> {
            if (a.equals("u@x.com")) {
                throw new IllegalStateException("boom");
            }
        };
        String bad = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL)).id();
        String good = f.service.submit("b", Fixture.hello("u2", ChannelType.EMAIL)).id();
        for (int i = 0; i < 5; i++) {
            f.service.runDue();
            f.clock.advance(Duration.ofSeconds(30));
        }
        Notification n = f.service.find(bad).get();
        assertEquals(Status.DEAD, n.status());
        assertEquals(3, n.attempts());
        assertTrue(n.reason().contains("IllegalStateException"));
        assertEquals(Status.SENT, f.service.find(good).get().status());
    }

    /** Proves a user who lost the address after submit is DEAD with a clear reason, and the transport is never called with null. */
    @Test
    void missingAddressAtSendTimeIsDead() {
        Fixture f = new Fixture(NOON, 100, 3);
        String id = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL)).id();
        f.prefs.put("u1", new UserPreferences(ZoneId.of("UTC"), Map.of(), Set.of(ChannelType.EMAIL), Optional.empty()));
        f.service.runDue();
        Notification n = f.service.find(id).get();
        assertEquals(Status.DEAD, n.status());
        assertEquals("no address for EMAIL", n.reason());
        assertEquals(0, f.calls.get());
    }

    /** Proves a user deleted after submit is suppressed, not an exception out of runDue. */
    @Test
    void userDeletedAfterSubmitIsSuppressed() {
        Fixture f = new Fixture(NOON, 100, 3);
        String id = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL)).id();
        f.prefs.remove("u1");
        f.service.runDue();
        assertEquals(Status.SUPPRESSED, f.service.find(id).get().status());
    }

    /** Proves a null variable value or key is a bad request, not a NullPointerException. */
    @Test
    void nullVariablesAreRejected() {
        Map<String, String> nullValue = new java.util.HashMap<>();
        nullValue.put("code", null);
        assertThrows(InvalidRequestException.class, () -> new NotificationRequest("u1", ChannelType.SMS, "hello", nullValue, Priority.NORMAL));
        Map<String, String> nullKey = new java.util.HashMap<>();
        nullKey.put(null, "x");
        assertThrows(InvalidRequestException.class, () -> new NotificationRequest("u1", ChannelType.SMS, "hello", nullKey, Priority.NORMAL));
    }

    /** Proves a null priority means NORMAL, so quiet hours hold the message. */
    @Test
    void nullPriorityIsHeldInQuietHours() {
        Fixture f = new Fixture(Instant.parse("2026-01-05T23:00:00Z"), 100, 3);
        f.addUser("u1", ZoneId.of("UTC"), Optional.of(Fixture.night()));
        String id = f.service.submit("a", new NotificationRequest("u1", ChannelType.EMAIL, "hello", Map.of("name", "A", "code", "1"), null)).id();
        f.service.runDue();
        assertEquals(Status.PENDING, f.service.find(id).get().status());
        assertEquals(0, f.calls.get());
    }

    /** Proves messages with the same due time go out in submit order, also past ten (ids n10 sort before n2 as text). */
    @Test
    void sameTimeMessagesGoOutInSubmitOrder() {
        Fixture f = new Fixture(NOON, 100, 3);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            expected.add(f.service.submit("k" + i, Fixture.hello("u1", ChannelType.EMAIL)).id());
        }
        f.service.runDue();
        assertEquals(expected, f.sentKeys);
    }

    /** Proves that when two messages are due at different times and the limit lets only one through, the one that has waited longest goes first. */
    @Test
    void olderDueTimeGoesFirstUnderPressure() {
        Fixture f = new Fixture(NOON, 1, 3);
        f.service.submit("x", Fixture.hello("u1", ChannelType.EMAIL));
        f.service.runDue();
        String b = f.service.submit("b", Fixture.hello("u1", ChannelType.EMAIL)).id();
        f.service.runDue();
        assertEquals(Instant.parse("2026-01-05T12:01:00Z"), f.service.find(b).get().nextAttemptAt());
        f.clock.set(Instant.parse("2026-01-05T12:00:30Z"));
        String c = f.service.submit("c", Fixture.hello("u1", ChannelType.EMAIL)).id();
        f.clock.set(Instant.parse("2026-01-05T12:01:00Z"));
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(c).get().status());
        assertEquals(Status.PENDING, f.service.find(b).get().status());
    }

    /** Proves the DLQ lists only dead messages, in submit order, even when a sent and a pending one exist. */
    @Test
    void dlqHoldsOnlyDeadMessages() {
        Fixture f = new Fixture(NOON, 100, 3);
        f.behaviour = n -> {
            if (n == 2) {
                throw ChannelException.permanentFailure("bad");
            }
        };
        String ok = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL)).id();
        String dead = f.service.submit("b", Fixture.hello("u1", ChannelType.EMAIL)).id();
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(ok).get().status());
        assertEquals(1, f.service.deadLetters().size());
        assertEquals(dead, f.service.deadLetters().get(0).id());
    }

    /** Proves the template text after the last placeholder is kept. */
    @Test
    void textAfterThePlaceholderIsKept() {
        assertEquals("Welcome Ann!", new Template("Welcome {{name}}!").render(Map.of("name", "Ann"), v -> v));
    }

    /** Proves a same-day window (13:00 to 15:00) ends today at 15:00, not tomorrow. */
    @Test
    void sameDayWindowEndsToday() {
        QuietHours q = new QuietHours(LocalTime.of(13, 0), LocalTime.of(15, 0));
        assertEquals(Instant.parse("2026-01-05T15:00:00Z"), q.endAfter(Instant.parse("2026-01-05T14:00:00Z"), ZoneId.of("UTC")).get());
    }

    /** Proves the SMS cut keeps the first 157 characters of a body whose characters differ, and the end is "...". */
    @Test
    void smsCutKeepsTheStart() {
        SmsChannel sms = new SmsChannel((a, b, k) -> { });
        String body = "a".repeat(100) + "b".repeat(100);
        assertEquals("a".repeat(100) + "b".repeat(57) + "...", sms.fit(body));
    }

    /** Proves the SMS cut never leaves half an emoji: a surrogate pair straddling the cut is dropped whole. */
    @Test
    void smsCutDoesNotSplitAnEmoji() {
        SmsChannel sms = new SmsChannel((a, b, k) -> { });
        String body = "a".repeat(156) + "😀" + "z".repeat(10);
        String cut = sms.fit(body);
        assertEquals("a".repeat(156) + "...", cut);
        assertTrue(cut.length() <= 160);
    }

    /** Proves push values are left raw (no HTML escaping). */
    @Test
    void pushLeavesValuesRaw() {
        assertEquals("<b>&", new PushChannel((a, b, k) -> { }).escape("<b>&"));
        assertEquals("<b>&", new SmsChannel((a, b, k) -> { }).escape("<b>&"));
    }

    /** Proves the retry policy constructor rejects zero attempts, a zero or sub-millisecond base, and a cap below the base. */
    @Test
    void retryPolicyChecksItsArguments() {
        java.util.Random r = new java.util.Random(1);
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2), r));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ZERO, Duration.ofSeconds(2), r));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofNanos(500), Duration.ofSeconds(2), r));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, Duration.ofSeconds(3), Duration.ofSeconds(2), r));
        assertEquals(1, new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1), r).maxAttempts());
    }

    /** Proves a null or blank key given straight to the core is an InvalidRequestException, not a NullPointerException. */
    @Test
    void nullKeyIsInvalidRequest() {
        Fixture f = new Fixture(NOON, 100, 3);
        assertThrows(InvalidRequestException.class, () -> f.service.submit(null, Fixture.hello("u1", ChannelType.EMAIL)));
    }

    /** Proves a repeat of an accepted key is answered even if the user was deleted since (the record is checked before rendering). */
    @Test
    void replayWorksAfterTheUserIsGone() {
        Fixture f = new Fixture(NOON, 100, 3);
        SubmitResult first = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL));
        f.prefs.remove("u1");
        SubmitResult again = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL));
        assertEquals(first.id(), again.id());
        assertTrue(again.replay());
    }

    /** Proves a user who moves zone (New York to Tokyo) while a message is held is judged in the new zone when the message comes due. */
    @Test
    void zoneChangeWhileHeldIsRecheckedAtRelease() {
        Fixture f = new Fixture(Instant.parse("2026-01-06T03:00:00Z"), 100, 3); // 22:00 in New York: quiet
        f.addUser("u1", ZoneId.of("America/New_York"), Optional.of(Fixture.night()));
        String id = f.service.submit("a", Fixture.hello("u1", ChannelType.EMAIL)).id();
        f.service.runDue();
        assertEquals(Instant.parse("2026-01-06T12:00:00Z"), f.service.find(id).get().nextAttemptAt());
        f.addUser("u1", ZoneId.of("Asia/Tokyo"), Optional.of(Fixture.night())); // 12:00Z is 21:00 in Tokyo: not quiet
        f.clock.set(Instant.parse("2026-01-06T12:00:00Z"));
        f.service.runDue();
        assertEquals(Status.SENT, f.service.find(id).get().status());
    }
}
