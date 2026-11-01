package com.lld.resilience;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Small helpers shared by the tests. */
final class Support {

    /** Not instantiable: this class only holds static helpers. */
    private Support() {
    }

    /** A sleeper that remembers each requested delay and returns at once. */
    static final class RecordingSleeper implements Sleeper {
        final List<Duration> delays = new ArrayList<>();

        /** Records the delay instead of waiting. */
        @Override
        public void sleep(Duration delay) {
            delays.add(delay);
        }
    }

    /** A dependency that fails with an IOException every time and counts how often it was called. */
    static final class AlwaysFails implements ThrowingSupplier<String> {
        final AtomicInteger calls = new AtomicInteger();

        /** Counts the call, then fails. */
        @Override
        public String get() throws IOException {
            calls.incrementAndGet();
            throw new IOException("down");
        }
    }

    /** Calls the breaker once and returns true if the call succeeded, false if the dependency failed; a rejection propagates. */
    static boolean tryOnce(CircuitBreaker cb, boolean succeed) {
        try {
            cb.call(() -> {
                if (!succeed) {
                    throw new IOException("boom");
                }
                return "ok";
            });
            return true;
        } catch (CircuitOpenException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }
}
