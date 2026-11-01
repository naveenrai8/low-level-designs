package com.lld.parkinglot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Two or more gates race for the same spot. Interleavings are forced with a barrier, not with sleeps. */
class ConcurrencyTest {

    private static final Map<VehicleType, Long> RATES = Map.of(VehicleType.MOTORCYCLE, 1L, VehicleType.CAR, 1L, VehicleType.TRUCK, 1L);

    /** Result of one gate: a ticket, or the error it got. */
    private record Outcome(Ticket ticket, Throwable error) {
    }

    /** Tries to park and turns a ParkingException into an Outcome, so racing threads never throw. */
    private static Outcome attempt(InMemoryParkingLot lot, Vehicle v) {
        try {
            return new Outcome(lot.park(v), null);
        } catch (ParkingException e) {
            return new Outcome(null, e);
        }
    }

    /** Proves that when two gates both see the last spot as free at the same moment (forced by a barrier inside the hook), exactly one gets it and the other gets LotFull. */
    @Test
    void twoGatesRacingForLastSpotExactlyOneWins() throws Exception {
        CyclicBarrier bothSeeFree = new CyclicBarrier(2);
        ClaimHook hook = spotId -> {
            try {
                bothSeeFree.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("gates never met", e);
            }
        };
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 1)), new HourlyPricing(RATES), new MutableClock(Instant.EPOCH), hook);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> attempt(lot, new Vehicle("A", VehicleType.CAR)));
            Future<Outcome> b = pool.submit(() -> attempt(lot, new Vehicle("B", VehicleType.CAR)));
            List<Outcome> results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(o -> o.ticket() != null).count());
            assertEquals(1, results.stream().filter(o -> o.error() instanceof ParkingException.LotFull).count());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves the loser of a forced race falls through to the next free spot instead of failing, so both gates succeed with different spots when two spots exist. */
    @Test
    void loserMovesToNextSpot() throws Exception {
        CyclicBarrier firstLook = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        ClaimHook hook = spotId -> {
            // Only the very first look by each gate waits; later looks pass straight through.
            if (calls.incrementAndGet() <= 2) {
                try {
                    firstLook.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 2)), new HourlyPricing(RATES), new MutableClock(Instant.EPOCH), hook);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> attempt(lot, new Vehicle("A", VehicleType.CAR)));
            Future<Outcome> b = pool.submit(() -> attempt(lot, new Vehicle("B", VehicleType.CAR)));
            Outcome oa = a.get(20, TimeUnit.SECONDS);
            Outcome ob = b.get(20, TimeUnit.SECONDS);
            assertEquals(null, oa.error());
            assertEquals(null, ob.error());
            assertEquals(2, List.of(oa.ticket().spotId(), ob.ticket().spotId()).stream().distinct().count());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves two gates parking the same plate at once: exactly one succeeds, the other gets DuplicateVehicle. */
    @Test
    void samePlateRacingParksOnce() throws Exception {
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 5)), new HourlyPricing(RATES), new MutableClock(Instant.EPOCH));
        for (int round = 0; round < 50; round++) {
            String plate = "P" + round;
            List<Outcome> results = runTogether(8, () -> attempt(lot, new Vehicle(plate, VehicleType.CAR)));
            assertEquals(1, results.stream().filter(o -> o.ticket() != null).count());
            assertEquals(7, results.stream().filter(o -> o.error() instanceof ParkingException.DuplicateVehicle).count());
            lot.unpark(results.stream().filter(o -> o.ticket() != null).findFirst().orElseThrow().ticket().id());
        }
    }

    /** Proves 64 gates released together onto 10 spots give out exactly 10 tickets with 10 distinct spots, and the other 54 get LotFull. */
    @Test
    void manyGatesGetExactlyTheCapacity() throws Exception {
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 4, SpotType.LARGE, 6)), new HourlyPricing(RATES), new MutableClock(Instant.EPOCH));
        AtomicInteger n = new AtomicInteger();
        List<Outcome> results = runTogether(64, () -> attempt(lot, new Vehicle("V" + n.incrementAndGet(), VehicleType.CAR)));
        List<Outcome> won = results.stream().filter(o -> o.ticket() != null).toList();
        assertEquals(10, won.size());
        assertEquals(10, won.stream().map(o -> o.ticket().spotId()).distinct().count());
        assertEquals(54, results.stream().filter(o -> o.error() instanceof ParkingException.LotFull).count());
    }

    /** Proves many threads unparking one ticket at once: exactly one gets a receipt, the rest get TicketAlreadyUsed. */
    @Test
    void ticketCanBeUsedByOnlyOneGate() throws Exception {
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 1)), new HourlyPricing(RATES), new MutableClock(Instant.EPOCH));
        Ticket t = lot.park(new Vehicle("A", VehicleType.CAR));
        List<Boolean> results = runTogether(16, () -> {
            try {
                lot.unpark(t.id());
                return true;
            } catch (ParkingException.TicketAlreadyUsed e) {
                return false;
            }
        });
        assertEquals(1, results.stream().filter(b -> b).count());
        assertEquals(1, lot.freeSpots(SpotType.MEDIUM));
    }

    /**
     * Proves the expiry-instant race cannot give one spot to two vehicles. Gate A passes its hold check one nanosecond before expiry and is paused by the hook. The clock then moves to the expiry instant and gate B takes the now-free spot. When A resumes, its take must fail with ReservationExpired.
     */
    @Test
    void parkReservedLosingTheExpiryRaceDoesNotOverwriteTheWinner() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CountDownLatch aPassedCheck = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        ClaimHook hook = new ClaimHook() {
            /** Does nothing: this test only needs the pause inside parkReserved. */
            @Override
            public void beforeClaim(String spotId) {
            }

            /** Pauses gate A between its hold check and its take until gate B has finished. */
            @Override
            public void afterHoldCheck(String reservationId) {
                aPassedCheck.countDown();
                try {
                    if (!bDone.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("B never finished");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 1)), new HourlyPricing(RATES), clock, hook);
        Reservation r = lot.reserve(new Vehicle("A", VehicleType.CAR), java.time.Duration.ofMinutes(10));
        clock.advance(java.time.Duration.ofMinutes(10).minusNanos(1));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Outcome> a = pool.submit(() -> {
                try {
                    return new Outcome(lot.parkReserved(r.id()), null);
                } catch (ParkingException e) {
                    return new Outcome(null, e);
                }
            });
            assertEquals(true, aPassedCheck.await(10, TimeUnit.SECONDS));
            clock.advance(java.time.Duration.ofNanos(1));
            Ticket b = lot.park(new Vehicle("B", VehicleType.CAR));
            bDone.countDown();
            Outcome oa = a.get(20, TimeUnit.SECONDS);
            assertEquals(true, oa.error() instanceof ParkingException.ReservationExpired);
            assertEquals(r.spotId(), b.spotId());
            // B still owns the spot: its ticket works once, and afterwards the spot is free.
            assertEquals(0, lot.freeSpots(SpotType.MEDIUM));
            lot.unpark(b.id());
            assertEquals(1, lot.freeSpots(SpotType.MEDIUM));
            // A's plate was not left claimed.
            lot.park(new Vehicle("A", VehicleType.CAR));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves a reserve and a park racing for the last spot (both forced to look before either takes) give the spot to exactly one of them, because both use one compare-and-set. */
    @Test
    void reserveAndParkRacingForLastSpotExactlyOneWins() throws Exception {
        CyclicBarrier bothSeeFree = new CyclicBarrier(2);
        ClaimHook hook = spotId -> {
            try {
                bothSeeFree.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("gates never met", e);
            }
        };
        InMemoryParkingLot lot = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 1)), new HourlyPricing(RATES), new MutableClock(Instant.EPOCH), hook);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> parker = pool.submit(() -> attempt(lot, new Vehicle("A", VehicleType.CAR)));
            Future<Throwable> reserver = pool.submit(() -> {
                try {
                    lot.reserve(new Vehicle("B", VehicleType.CAR), java.time.Duration.ofMinutes(5));
                    return null;
                } catch (ParkingException e) {
                    return e;
                }
            });
            boolean parked = parker.get(20, TimeUnit.SECONDS).ticket() != null;
            boolean reserved = reserver.get(20, TimeUnit.SECONDS) == null;
            assertEquals(1, (parked ? 1 : 0) + (reserved ? 1 : 0));
            assertEquals(0, lot.freeSpots(SpotType.MEDIUM));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Starts the given number of threads behind one start gate so they begin together, and collects every result. */
    private static <T> List<T> runTogether(int threads, Callable<T> task) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> out = new java.util.ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
