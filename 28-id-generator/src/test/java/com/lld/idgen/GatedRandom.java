package com.lld.idgen;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;

/** A seeded random source whose very first draw parks the calling thread until the test releases it. Used to freeze one thread in the middle of a generator call, to prove another thread cannot get in. */
final class GatedRandom implements RandomGenerator {

    private final Random delegate = new Random(1234);
    private final AtomicBoolean first = new AtomicBoolean(true);
    private volatile boolean entered;
    private volatile boolean released;

    /** Returns the next seeded value. The first caller parks here until {@link #release()} is called. */
    @Override
    public long nextLong() {
        if (first.compareAndSet(true, false)) {
            entered = true;
            while (!released) {
                Thread.onSpinWait();
            }
        }
        synchronized (delegate) {
            return delegate.nextLong();
        }
    }

    /** Spins until the first caller is parked inside {@link #nextLong()}. */
    void awaitEntered() {
        while (!entered) {
            Thread.onSpinWait();
        }
    }

    /** Lets the parked thread carry on. */
    void release() {
        released = true;
    }

    /**
     * Starts two threads that both call {@code task}: the first is parked inside the generator, then the second is started. Returns the second thread's state once it has either finished or blocked on a lock, as a string ("BLOCKED" or "TERMINATED"), then releases the first and joins both.
     */
    static String raceTwoThreads(GatedRandom gate, Runnable task) throws InterruptedException {
        Thread a = new Thread(task);
        Thread b = new Thread(task);
        a.start();
        gate.awaitEntered();
        b.start();
        while (b.getState() != Thread.State.BLOCKED && b.getState() != Thread.State.TERMINATED) {
            Thread.onSpinWait();
        }
        String state = b.getState().name();
        gate.release();
        a.join(5_000);
        b.join(5_000);
        return state;
    }
}
