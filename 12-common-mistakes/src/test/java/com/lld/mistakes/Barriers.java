package com.lld.mistakes;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Barriers {

    private Barriers() { }

    /** A {@link Gap} that holds each thread until {@code parties} threads are inside the gap. */
    static Gap meetIn(int parties) {
        CyclicBarrier barrier = new CyclicBarrier(parties);
        return () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException("threads never met in the gap", e);
            }
        };
    }
}
