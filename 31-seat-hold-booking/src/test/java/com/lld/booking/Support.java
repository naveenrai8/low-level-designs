package com.lld.booking;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Small helpers shared by the tests. */
final class Support {

    static final BookingConfig CONFIG = new BookingConfig(Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofMinutes(30));

    /** Not meant to be created. */
    private Support() {
    }

    /** Returns a supplier of ids like h-1, h-2, ... safe to call from many threads. */
    static Supplier<String> ids(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return () -> prefix + n.incrementAndGet();
    }

    /** Builds a service with a show "s" of seats A1..A{count}. */
    static SeatHoldBookingService service(TestClock clock, int count) {
        SeatHoldBookingService svc = new SeatHoldBookingService(clock, CONFIG, ids("h-"), ids("b-"));
        svc.addShow("s", seats(count));
        return svc;
    }

    /** Builds a service with a show "s" of {@code count} seats, custom id suppliers and a gap hook. */
    static SeatHoldBookingService service(TestClock clock, int count, Supplier<String> holdIds, Supplier<String> bookingIds, Gap gap) {
        SeatHoldBookingService svc = new SeatHoldBookingService(clock, CONFIG, holdIds, bookingIds, OverbookPolicy.NONE, LockOrder.SORTED, gap);
        svc.addShow("s", seats(count));
        return svc;
    }

    /** Spins (no sleeping) until the thread is parked or finished, or fails after 10 seconds. Used to know a thread is blocked on a lock. */
    static void awaitBlockedOrDone(Thread t) {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (t.isAlive() && t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > end) {
                throw new AssertionError("thread never blocked");
            }
            Thread.onSpinWait();
        }
    }

    /** Returns the seat ids A1..A{count}. */
    static Set<String> seats(int count) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (int i = 1; i <= count; i++) {
            out.add("A" + i);
        }
        return out;
    }

    /** Shorthand for a list. */
    static List<String> l(String... s) {
        return List.of(s);
    }
}
