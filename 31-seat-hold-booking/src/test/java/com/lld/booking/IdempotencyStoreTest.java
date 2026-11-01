package com.lld.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The key store on its own: expiry must not depend on the order entries were added in. */
class IdempotencyStoreTest {

    private static final Instant T0 = TestClock.T0;

    /** Builds a booked outcome for the tests. */
    private static IdempotencyStore.Outcome booked(String id) {
        return new IdempotencyStore.Booked(new Booking(id, "h", "s", java.util.List.of("A1"), "p", T0));
    }

    /** Proves an expired entry sitting BEHIND a live one (possible after a clock step) is still treated as absent, because lookup checks the entry's own expiry and not just the front of the queue. */
    @Test
    void expiredEntryBehindALiveOneIsAbsent() {
        IdempotencyStore store = new IdempotencyStore();
        IdempotencyStore.Fingerprint f = new IdempotencyStore.Fingerprint("h", "p");
        store.put("live", f, booked("b-1"), T0.plus(Duration.ofHours(10)));
        store.put("early", f, booked("b-2"), T0.plus(Duration.ofHours(1)));
        Instant later = T0.plus(Duration.ofHours(1));
        assertTrue(store.lookup("early", f, later).isEmpty(), "expired AT its instant");
        assertTrue(store.lookup("live", f, later).isPresent());
        assertEquals(1, store.lookup("early", f, T0).map(o -> 1).orElse(0), "one tick earlier it is still there");
    }
}
