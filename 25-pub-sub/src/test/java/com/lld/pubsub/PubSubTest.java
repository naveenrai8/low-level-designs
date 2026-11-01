package com.lld.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Single-thread behaviour tests: offsets per group, ack/nack/deadline redelivery, the DLQ, seek and lag limits. */
class PubSubTest {
    private static final long SEC = 1_000_000_000L;
    private long now;
    private InMemoryPubSub ps;

    /** Builds a broker on a fake clock and a topic "t". */
    @BeforeEach
    void setUp() {
        now = 0;
        ps = new InMemoryPubSub(() -> now);
        ps.createTopic("t");
    }

    /** Makes a group config with the given attempt limit and a 10 second deadline. */
    private SubscriptionConfig cfg(int attempts) {
        return new SubscriptionConfig(attempts, 10 * SEC, StartAt.EARLIEST);
    }

    /** Polls and acks until nothing is left, returning the offsets seen in order. */
    private List<Long> drain(String group) {
        List<Long> offsets = new ArrayList<>();
        Optional<Delivery> d;
        while ((d = ps.poll("t", group)).isPresent()) {
            offsets.add(d.get().message().offset());
            ps.ack("t", group, d.get());
        }
        return offsets;
    }

    /** Risk 1: draining group A to the end leaves group B at offset 0. */
    @Test
    void groupsReadIndependently() {
        ps.subscribe("t", "A", cfg(3));
        ps.subscribe("t", "B", cfg(3));
        for (int i = 0; i < 5; i++) {
            ps.publish("t", "k" + i, "p" + i);
        }
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), drain("A"));
        assertEquals(5, ps.position("t", "A"));
        assertEquals(0, ps.position("t", "B"));
        assertEquals(5, ps.lag("t", "B"));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), drain("B"));
    }

    /** Risk 1: rewinding group A replays for A only; B, half way through, does not move. */
    @Test
    void rewindingOneGroupNeverMovesAnother() {
        ps.subscribe("t", "A", cfg(3));
        ps.subscribe("t", "B", cfg(3));
        for (int i = 0; i < 4; i++) {
            ps.publish("t", "k" + i, "p");
        }
        drain("A");
        Delivery b0 = ps.poll("t", "B").orElseThrow();
        ps.ack("t", "B", b0);
        Delivery b1 = ps.poll("t", "B").orElseThrow();
        ps.seek("t", "A", 1);
        assertEquals(1, ps.position("t", "A"));
        assertEquals(2, ps.position("t", "B"));
        assertEquals(3, ps.lag("t", "A"));
        assertTrue(ps.ack("t", "B", b1), "B's in-flight receipt must survive A's seek");
        assertEquals(List.of(1L, 2L, 3L), drain("A"));
        assertEquals(List.of(2L, 3L), drain("B"));
    }

    /** A LATEST group sees only messages published after it subscribes. */
    @Test
    void latestStartSkipsHistory() {
        ps.publish("t", "k", "old");
        ps.subscribe("t", "L", new SubscriptionConfig(3, SEC, StartAt.LATEST));
        ps.publish("t", "k", "new");
        assertEquals(List.of(1L), drain("L"));
    }

    /** An acked message is never delivered again, even after the deadline would have passed. */
    @Test
    void ackedMessageIsNotRedelivered() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "k", "p");
        Delivery d = ps.poll("t", "g").orElseThrow();
        assertTrue(ps.ack("t", "g", d));
        now += 100 * SEC;
        assertTrue(ps.poll("t", "g").isEmpty());
        assertFalse(ps.ack("t", "g", d), "double ack is stale");
    }

    /** Risk 2: with maxAttempts 3, nacking each delivery gives exactly 3 deliveries then a DLQ entry with attempts=3. */
    @Test
    void nackThreeTimesGoesToDlqAfterExactlyThreeDeliveries() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "k", "p");
        int deliveries = 0;
        Optional<Delivery> d;
        while ((d = ps.poll("t", "g")).isPresent()) {
            deliveries++;
            assertEquals(deliveries, d.get().attempt());
            assertTrue(ps.nack("t", "g", d.get()));
        }
        assertEquals(3, deliveries);
        List<DeadLetter> dlq = ps.deadLetters("t", "g");
        assertEquals(1, dlq.size());
        assertEquals(3, dlq.get(0).attempts());
        assertEquals(0, dlq.get(0).message().offset());
        assertEquals("nack", dlq.get(0).reason());
        assertEquals(0, ps.lag("t", "g"));
    }

    /** Risk 2: a consumer that never acks. Each missed deadline costs one attempt; after N the message is dead-lettered, with no sleeping. */
    @Test
    void missedDeadlineRedeliversThenDeadLetters() {
        ps.subscribe("t", "g", cfg(2));
        ps.publish("t", "k", "p");
        Delivery first = ps.poll("t", "g").orElseThrow();
        now += 9 * SEC;
        assertTrue(ps.poll("t", "g").isEmpty(), "still within the deadline, nothing to redeliver");
        now += SEC;
        Delivery second = ps.poll("t", "g").orElseThrow();
        assertEquals(2, second.attempt());
        assertFalse(ps.ack("t", "g", first), "the old receipt is stale and must not settle attempt 2");
        now += 10 * SEC;
        assertTrue(ps.poll("t", "g").isEmpty(), "attempt 3 must never happen");
        List<DeadLetter> dlq = ps.deadLetters("t", "g");
        assertEquals(1, dlq.size());
        assertEquals(2, dlq.get(0).attempts());
        assertEquals("ack deadline expired", dlq.get(0).reason());
    }

    /** Risk 2: a late ack (after the deadline) is refused, and the message is redelivered rather than lost. */
    @Test
    void lateAckIsRefusedAndMessageComesBack() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "k", "p");
        Delivery d = ps.poll("t", "g").orElseThrow();
        now += 10 * SEC;
        assertFalse(ps.ack("t", "g", d));
        assertEquals(2, ps.poll("t", "g").orElseThrow().attempt());
    }

    /** Risk 2: a message that fails twice then succeeds is acked on attempt 3 and never dead-lettered. */
    @Test
    void successOnLastAttemptAvoidsDlq() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "k", "p");
        ps.nack("t", "g", ps.poll("t", "g").orElseThrow());
        ps.nack("t", "g", ps.poll("t", "g").orElseThrow());
        assertTrue(ps.ack("t", "g", ps.poll("t", "g").orElseThrow()));
        assertTrue(ps.deadLetters("t", "g").isEmpty());
    }

    /** Dead-lettering one message does not stop the messages behind it. */
    @Test
    void poisonMessageDoesNotBlockLaterOnes() {
        ps.subscribe("t", "g", cfg(1));
        ps.publish("t", "k", "poison");
        ps.publish("t", "k", "fine");
        ps.nack("t", "g", ps.poll("t", "g").orElseThrow());
        assertEquals(1, ps.deadLetters("t", "g").size());
        assertEquals(1, ps.poll("t", "g").orElseThrow().message().offset());
    }

    /** One group's DLQ is its own: another group acking the same message has an empty DLQ. */
    @Test
    void dlqIsPerGroup() {
        ps.subscribe("t", "bad", cfg(1));
        ps.subscribe("t", "good", cfg(1));
        ps.publish("t", "k", "p");
        ps.nack("t", "bad", ps.poll("t", "bad").orElseThrow());
        ps.ack("t", "good", ps.poll("t", "good").orElseThrow());
        assertEquals(1, ps.deadLetters("t", "bad").size());
        assertTrue(ps.deadLetters("t", "good").isEmpty());
    }

    /** Same-key messages are handed out one at a time and in order, while another key is not blocked. */
    @Test
    void sameKeyIsSerialAndDifferentKeyIsNot() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "a", "a0");
        ps.publish("t", "a", "a1");
        ps.publish("t", "b", "b0");
        Delivery a0 = ps.poll("t", "g").orElseThrow();
        Delivery b0 = ps.poll("t", "g").orElseThrow();
        assertEquals(0, a0.message().offset());
        assertEquals(2, b0.message().offset(), "key b jumps ahead of the blocked a1");
        assertTrue(ps.poll("t", "g").isEmpty(), "a1 must wait for a0");
        ps.nack("t", "g", a0);
        Delivery again = ps.poll("t", "g").orElseThrow();
        assertEquals(0, again.message().offset(), "a0 is retried before a1");
        ps.ack("t", "g", again);
        assertEquals(1, ps.poll("t", "g").orElseThrow().message().offset());
    }

    /** F3: publish fails once a group's lag reaches the limit, appends nothing, and works again after acks. */
    @Test
    void backpressureRejectsPublishAtLagLimit() {
        ps.createTopic("bp", 2);
        ps.subscribe("bp", "slow", cfg(3));
        ps.publish("bp", "k", "1");
        ps.publish("bp", "k", "2");
        assertThrows(BackpressureException.class, () -> ps.publish("bp", "k", "3"));
        assertEquals(2, ps.lag("bp", "slow"));
        ps.ack("bp", "slow", ps.poll("bp", "slow").orElseThrow());
        assertEquals(2, ps.publish("bp", "k", "3"));
    }

    /** The error contract: unknown names and duplicates are rejected, bad config is refused. */
    @Test
    void errorContract() {
        ps.subscribe("t", "g", cfg(1));
        assertThrows(IllegalArgumentException.class, () -> ps.publish("nope", "k", "p"));
        assertThrows(IllegalArgumentException.class, () -> ps.poll("t", "nope"));
        assertThrows(IllegalStateException.class, () -> ps.subscribe("t", "g", cfg(1)));
        assertThrows(IllegalStateException.class, () -> ps.createTopic("t"));
        assertThrows(IllegalArgumentException.class, () -> ps.seek("t", "g", 5));
        assertThrows(IllegalArgumentException.class, () -> new SubscriptionConfig(0, 1, StartAt.EARLIEST));
        assertThrows(IllegalArgumentException.class, () -> new SubscriptionConfig(1, 0, StartAt.EARLIEST));
    }

    /** Item 4: a receipt from topic t1 must not settle a message in topic t2, even though both are offset 0; t2's key stays usable. */
    @Test
    void receiptFromAnotherTopicIsStale() {
        ps.createTopic("t2");
        ps.subscribe("t", "g", cfg(3));
        ps.subscribe("t2", "g", cfg(3));
        ps.publish("t", "a", "p");
        ps.publish("t2", "b", "p");
        ps.publish("t2", "b", "p");
        Delivery fromT1 = ps.poll("t", "g").orElseThrow();
        Delivery fromT2 = ps.poll("t2", "g").orElseThrow();
        assertFalse(ps.ack("t2", "g", fromT1), "a t1 receipt must not ack t2's message");
        assertTrue(ps.poll("t2", "g").isEmpty(), "key b is still in flight, so offset 1 must wait");
        assertTrue(ps.ack("t2", "g", fromT2));
        assertEquals(1, ps.poll("t2", "g").orElseThrow().message().offset());
    }

    /** Item 5: when the only attempt's deadline has passed, lag no longer counts that message. */
    @Test
    void expiredLastAttemptLeavesLag() {
        ps.subscribe("t", "g", cfg(1));
        ps.publish("t", "k", "p");
        ps.poll("t", "g").orElseThrow();
        assertEquals(1, ps.lag("t", "g"));
        now += 10 * SEC;
        assertEquals(0, ps.lag("t", "g"));
    }

    /** Item 5: deadLetters() runs expiry, so an expired last attempt shows up without any other traffic. */
    @Test
    void expiredLastAttemptShowsInDeadLetters() {
        ps.subscribe("t", "g", cfg(1));
        ps.publish("t", "k", "p");
        ps.poll("t", "g").orElseThrow();
        now += 10 * SEC;
        assertEquals(1, ps.deadLetters("t", "g").size());
    }

    /** Item 5: publish's lag check runs expiry, so an expired last attempt cannot cause a false BackpressureException. */
    @Test
    void expiredLastAttemptDoesNotCauseFalseBackpressure() {
        ps.createTopic("bp", 1);
        ps.subscribe("bp", "g", cfg(1));
        ps.publish("bp", "k", "p");
        ps.poll("bp", "g").orElseThrow();
        now += 10 * SEC;
        assertEquals(1, ps.publish("bp", "k", "q"));
    }

    /** Item 5: position() also settles expiry first, so a seek or read never works on stale in-flight state. */
    @Test
    void seekAfterExpiryDoesNotResurrectDeadLetter() {
        ps.subscribe("t", "g", cfg(1));
        ps.publish("t", "k", "p");
        ps.poll("t", "g").orElseThrow();
        now += 10 * SEC;
        ps.seek("t", "g", 1);
        assertEquals(1, ps.deadLetters("t", "g").size(), "the expired attempt must be dead-lettered before seek forgets it");
    }

    /** Item 6: the rule is one DLQ entry per offset. Seeking back past a dead letter and failing it again replaces the entry instead of duplicating it. */
    @Test
    void seekBackPastDeadLetterDoesNotDuplicateDlq() {
        ps.subscribe("t", "g", cfg(1));
        ps.publish("t", "k", "p");
        ps.nack("t", "g", ps.poll("t", "g").orElseThrow());
        ps.seek("t", "g", 0);
        ps.nack("t", "g", ps.poll("t", "g").orElseThrow());
        assertEquals(1, ps.deadLetters("t", "g").size());
    }

    /** Item 7: seek forgets in-flight and retry-waiting work, so old receipts are stale and nothing forgotten is redelivered. */
    @Test
    void seekForgetsInFlightAndRetryWaitingWork() {
        ps.subscribe("t", "g", cfg(3));
        for (int i = 0; i < 3; i++) {
            ps.publish("t", "k" + i, "p");
        }
        Delivery inFlight0 = ps.poll("t", "g").orElseThrow();
        Delivery retry1 = ps.poll("t", "g").orElseThrow();
        ps.nack("t", "g", retry1);
        Delivery inFlight2 = ps.poll("t", "g").orElseThrow();
        ps.seek("t", "g", 3);
        assertFalse(ps.ack("t", "g", inFlight0));
        assertFalse(ps.ack("t", "g", inFlight2));
        assertTrue(ps.poll("t", "g").isEmpty(), "the retry-waiting message was forgotten");
        assertEquals(0, ps.lag("t", "g"));
    }

    /** Item 7: after a seek back, the key is free again and the first delivery is a fresh attempt 1; the old receipt stays stale. */
    @Test
    void seekBackMakesOldReceiptStaleAndKeyFree() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "k", "p");
        Delivery old = ps.poll("t", "g").orElseThrow();
        ps.seek("t", "g", 0);
        Delivery fresh = ps.poll("t", "g").orElseThrow();
        assertEquals(1, fresh.attempt());
        assertFalse(ps.ack("t", "g", old));
        assertTrue(ps.ack("t", "g", fresh));
    }

    /** Item 7: lag counts unread, retry-waiting and in-flight messages, so it is not just log size minus cursor. */
    @Test
    void lagCountsUnreadRetryAndInFlight() {
        ps.subscribe("t", "g", cfg(3));
        for (int i = 0; i < 4; i++) {
            ps.publish("t", "k" + i, "p");
        }
        Delivery a = ps.poll("t", "g").orElseThrow();
        Delivery b = ps.poll("t", "g").orElseThrow();
        ps.nack("t", "g", b);
        assertEquals(4, ps.lag("t", "g"), "2 unread + 1 retry-waiting + 1 in flight");
        ps.ack("t", "g", a);
        assertEquals(3, ps.lag("t", "g"));
    }

    /** Item 7: seeking to exactly the log size is allowed and means "only new messages from now on". */
    @Test
    void seekToExactlyLogSizeIsAllowed() {
        ps.subscribe("t", "g", cfg(3));
        ps.publish("t", "k", "p");
        ps.publish("t", "k", "p");
        ps.seek("t", "g", 2);
        assertEquals(2, ps.position("t", "g"));
        assertTrue(ps.poll("t", "g").isEmpty());
        ps.publish("t", "k", "p");
        assertEquals(2, ps.poll("t", "g").orElseThrow().message().offset());
    }
}
