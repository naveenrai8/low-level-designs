package com.lld.connpool;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Test doubles shared by the pool tests: a hand-moved ticker and a factory that counts opens, closes and the peak of live connections. */
final class Fakes {

    /** Not instantiable: this class only groups the fakes. */
    private Fakes() {
    }

    /** A ticker that only moves when a test says so. */
    static final class ManualTicker implements Ticker {
        private final AtomicLong now = new AtomicLong(1_000);

        /** Returns the current fake reading. */
        @Override
        public long read() {
            return now.get();
        }

        /** Moves the fake clock forward by the given nanoseconds. */
        void advance(long nanos) {
            now.addAndGet(nanos);
        }
    }

    /** A fake connection that can be told to go bad and remembers if it was closed. */
    static final class FakeConn implements Connection {
        private final long id;
        private final Factory owner;
        volatile boolean valid = true;
        volatile boolean throwOnValidate;
        volatile boolean closed;

        /** Creates a connection with a unique id belonging to the given factory. */
        FakeConn(long id, Factory owner) {
            this.id = id;
            this.owner = owner;
        }

        /** Returns the id. */
        @Override
        public long id() {
            return id;
        }

        /** Answers the health check from the {@code valid} flag, or throws if told to. */
        @Override
        public boolean isValid() {
            Runnable hook = owner.onValidate;
            if (hook != null) {
                hook.run();
            }
            if (throwOnValidate) {
                throw new IllegalStateException("validation blew up");
            }
            return valid;
        }

        /** Marks the connection closed and lowers the live count, once. */
        @Override
        public synchronized void close() {
            CountDownLatch gate = owner.closeGate;
            if (gate != null) {
                owner.closeEntered.release();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!closed) {
                closed = true;
                owner.live.decrementAndGet();
                owner.closes.incrementAndGet();
            }
        }
    }

    /** A factory that counts live connections (opened minus closed), keeps the peak, and can be told to fail. */
    static final class Factory implements ConnectionFactory {
        final AtomicInteger live = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger failNext = new AtomicInteger();
        /** Every connection ever opened, in order, so a test can flip one bad without casting a lent connection. */
        final List<FakeConn> made = new CopyOnWriteArrayList<>();
        /** When set, every {@code close()} signals {@code closeEntered} and then waits here, so a test can hold a close half-way. */
        volatile CountDownLatch closeGate;
        final Semaphore closeEntered = new Semaphore(0);
        /** When set, runs inside every {@code isValid()}, so a test can make validation "take time" on the fake ticker. */
        volatile Runnable onValidate;
        private final AtomicLong ids = new AtomicLong();

        /** Opens a fake connection, or throws while {@code failNext} is above zero. */
        @Override
        public Connection create() {
            if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new ConnectionException("database down");
            }
            creates.incrementAndGet();
            int now = live.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            FakeConn c = new FakeConn(ids.incrementAndGet(), this);
            made.add(c);
            return c;
        }
    }
}
