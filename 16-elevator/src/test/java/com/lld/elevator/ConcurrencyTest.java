package com.lld.elevator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Proves the building lock: dispatch (read cars, pick, write to one car) is one atomic step, and no request is lost under contention. */
class ConcurrencyTest {

    /** A deliberately broken building: it picks a car, then waits at a hook, then writes. It shows the race the real lock prevents. */
    private static final class UnlockedBuilding {
        private final List<Car> cars = new ArrayList<>();
        private final Dispatcher dispatcher = new LeastLoadedDispatcher();
        private final Runnable gap;

        /** Creates two idle cars; {@code gap} runs between the pick and the write. */
        UnlockedBuilding(Runnable gap) {
            cars.add(new Car(0, 0));
            cars.add(new Car(1, 0));
            this.gap = gap;
        }

        /** The same steps as the real dispatch, but with no lock and a gap between read and write. Returns the chosen car id. */
        int hallCall(int floor) {
            List<CarView> views = cars.stream().map(Car::view).toList();
            int id = dispatcher.pick(new HallCall(floor, Direction.UP), views);
            gap.run();
            cars.get(id).addHall(floor, Direction.UP);
            return id;
        }
    }

    /** The bug: with the gap forced by a barrier, two threads both see two empty cars and both pick car 0. */
    @Test
    void unlockedDispatchLetsTwoThreadsPickTheSameCar() throws Exception {
        CyclicBarrier bothPicked = new CyclicBarrier(2);
        UnlockedBuilding b = new UnlockedBuilding(() -> {
            try {
                bothPicked.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> f1 = pool.submit(() -> b.hallCall(3));
        Future<Integer> f2 = pool.submit(() -> b.hallCall(4));
        assertEquals(0, f1.get());
        assertEquals(0, f2.get());
        pool.shutdown();
    }

    /** The fix: a dispatcher that records whether the building's lock is held when it is asked to pick. */
    @Test
    void realDispatchRunsUnderTheBuildingLock() {
        Building[] holder = new Building[1];
        AtomicBoolean heldAlways = new AtomicBoolean(true);
        Dispatcher probe = (call, eligible) -> {
            heldAlways.compareAndSet(true, Thread.holdsLock(holder[0]));
            return eligible.get(0).id();
        };
        Building b = new Building(10, 2, probe, new VirtualTicker());
        holder[0] = b;
        b.hallCall(3, Direction.UP);
        b.setMaintenance(1, true);
        assertTrue(heldAlways.get(), "dispatcher must be called while holding the building lock");
    }

    /** 8 threads each press 500 distinct hall calls behind a start gate; afterwards exactly 4000 are pending (distinct floor and direction pairs never collapse). */
    @Test
    void noRequestIsLost() throws Exception {
        int threads = 8;
        int each = 500;
        // 4001 floors so every thread has its own 500 floors; direction UP.
        Building b = new Building(threads * each + 1, 4, new LeastLoadedDispatcher(), new VirtualTicker());
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int base = t * each;
            fs.add(pool.submit(() -> {
                gate.await();
                for (int i = 0; i < each; i++) {
                    b.hallCall(base + i, Direction.UP);
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        assertEquals(threads * each, b.pending());
    }
}
