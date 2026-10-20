package com.lld.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SeatMapTest {

    /**
     * Deterministic, not probabilistic: the barrier only opens once BOTH threads have passed the
     * "is it free?" check, so both are guaranteed to act on a stale answer.
     */
    @Test
    void bugCheckThenActDoubleBooksWhenTwoThreadsShareTheWindow() throws Exception {
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        SeatMap seats = new CheckThenActSeatMap(() -> await(bothChecked));
        AtomicInteger winners = new AtomicInteger();

        Throwable failure = Race.run(2, t -> () -> {
            if (seats.book(7, "user-" + t)) {
                winners.incrementAndGet();
            }
        });

        assertNull(failure);
        assertEquals(2, winners.get(), "both users were told they hold seat 7");
    }

    @Test
    void fixExactlyOneWinnerPerSeatUnderHeavyContention() throws Exception {
        SeatMap seats = new AtomicSeatMap();
        int seatCount = 100;
        ConcurrentHashMap<Integer, String> winnerBySeat = new ConcurrentHashMap<>();
        AtomicInteger wins = new AtomicInteger();

        Throwable failure = Race.run(32, t -> () -> {
            for (int seat = 0; seat < seatCount; seat++) {
                String user = "user-" + t;
                if (seats.book(seat, user)) {
                    wins.incrementAndGet();
                    winnerBySeat.put(seat, user);
                }
            }
        });

        assertNull(failure);
        assertEquals(seatCount, wins.get(), "exactly one winner per seat");
        for (int seat = 0; seat < seatCount; seat++) {
            assertEquals(winnerBySeat.get(seat), seats.holderOf(seat).orElseThrow(),
                    "the user told they won is the user who holds the seat");
        }
        assertTrue(true);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
