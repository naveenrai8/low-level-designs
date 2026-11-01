package com.lld.blockingqueue;

import java.util.function.Consumer;

/**
 * Two tiny callbacks that let a test stop the queue at exactly the interesting moments. Production code uses {@link #NONE}, which does nothing.
 *
 * <p>Both callbacks run <em>while the queue's lock is held</em>. That is the point: a test can force an exact interleaving of threads without sleeping. A callback may call the non-waiting methods of the same queue (the lock is re-entrant). It must NOT block or call {@code put}/{@code take}: {@code await} releases every hold of the re-entrant lock, so the outer operation would no longer be atomic. Only test code that wants to show exactly that breakage does it.
 */
public interface QueueHooks {

    /** Which of the two waiting rooms a callback is about. */
    enum Side {
        /** The room where takers wait because the queue is empty. */
        NOT_EMPTY,
        /** The room where putters wait because the queue is full. */
        NOT_FULL
    }

    /** A hook set that does nothing. */
    QueueHooks NONE = new Hooks(side -> { }, side -> { });

    /** Called just before a thread goes to sleep in the given waiting room. The lock is still held, and sleeping releases it. */
    void beforeAwait(Side side);

    /** Called right after a thread signals the given waiting room, still holding the lock. The woken thread cannot run until the lock is released. */
    void afterSignal(Side side);

    /** Builds a hook set from two lambdas. */
    static QueueHooks of(Consumer<Side> beforeAwait, Consumer<Side> afterSignal) {
        return new Hooks(beforeAwait, afterSignal);
    }

    /** The simple lambda-backed implementation behind {@link #of} and {@link #NONE}. */
    final class Hooks implements QueueHooks {
        private final Consumer<Side> beforeAwait;
        private final Consumer<Side> afterSignal;

        /** Stores the two callbacks. */
        private Hooks(Consumer<Side> beforeAwait, Consumer<Side> afterSignal) {
            this.beforeAwait = beforeAwait;
            this.afterSignal = afterSignal;
        }

        /** Forwards to the stored callback. */
        @Override
        public void beforeAwait(Side side) {
            beforeAwait.accept(side);
        }

        /** Forwards to the stored callback. */
        @Override
        public void afterSignal(Side side) {
            afterSignal.accept(side);
        }
    }
}
