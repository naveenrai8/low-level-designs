package com.lld.idgen;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snowflake-style generator: 41-bit time, 10-bit node, 12-bit sequence (see {@link SnowflakeLayout}).
 *
 * <p>Concurrency model: the only shared state is one {@link AtomicLong} that packs {@code (lastTimestamp << 12 | lastSequence)}. Each call reads it, works out the next value, and publishes it with compare-and-set (CAS: "write only if nobody changed it since I read it"). A loser simply re-reads and tries again. No lock, and the packed value only ever grows, so every ID is bigger than the one before.
 *
 * <p>Clock policy: if the clock is behind the last issued timestamp by at most {@code maxBackwardsMillis}, keep using the last timestamp and keep counting the sequence (we never reuse a pair). If it is further behind, throw {@link ClockMovedBackwardsException}. If the sequence runs out inside one millisecond, call {@link Waiter#pause()} until the clock moves past it.
 */
public final class SnowflakeIdGenerator implements IdGenerator {

    private static final int SEQUENCE_BITS = SnowflakeLayout.SEQUENCE_BITS;
    private static final long SEQUENCE_MASK = SnowflakeLayout.MAX_SEQUENCE;

    private final Clock clock;
    private final Waiter waiter;
    private final int node;
    private final long epochMillis;
    private final long maxBackwardsMillis;
    private final Runnable beforeCas;
    /** Packed {@code (timestamp << 12) | sequence} of the last ID issued. -1 means nothing issued yet. */
    private final AtomicLong state = new AtomicLong(-1L);

    /**
     * Creates a generator.
     *
     * @param epochMillis Unix ms that counts as time zero; IDs stay valid for 2^41 ms after it
     * @param maxBackwardsMillis how far back the clock may step before we throw; 0 means any step back throws
     */
    public SnowflakeIdGenerator(Clock clock, Waiter waiter, int node, long epochMillis, long maxBackwardsMillis) {
        this(clock, waiter, node, epochMillis, maxBackwardsMillis, () -> { });
    }

    /** Test-only constructor: {@code beforeCas} runs after the next value is computed and before it is published, so a test can force two threads into the gap. */
    SnowflakeIdGenerator(Clock clock, Waiter waiter, int node, long epochMillis, long maxBackwardsMillis, Runnable beforeCas) {
        if (node < 0 || node > SnowflakeLayout.MAX_NODE) {
            throw new IllegalArgumentException("node out of range: " + node);
        }
        if (maxBackwardsMillis < 0) {
            throw new IllegalArgumentException("maxBackwardsMillis must be >= 0");
        }
        this.clock = Objects.requireNonNull(clock);
        this.waiter = Objects.requireNonNull(waiter);
        this.node = node;
        this.epochMillis = epochMillis;
        this.maxBackwardsMillis = maxBackwardsMillis;
        this.beforeCas = Objects.requireNonNull(beforeCas);
    }

    /** Issues the next ID. Retries on a lost CAS, and waits through {@link Waiter} when the sequence is exhausted. */
    @Override
    public long nextId() {
        while (true) {
            long previous = state.get();
            long lastTs = previous < 0 ? -1 : previous >>> SEQUENCE_BITS;
            long lastSeq = previous < 0 ? -1 : previous & SEQUENCE_MASK;
            long now = clock.millis() - epochMillis;
            if (now < 0) {
                throw new IllegalStateException("clock is before the epoch");
            }
            long ts = now;
            if (now < lastTs) {
                long drift = lastTs - now;
                if (drift > maxBackwardsMillis) {
                    throw new ClockMovedBackwardsException(drift, maxBackwardsMillis);
                }
                ts = lastTs; // small step back: stay in the last millisecond and keep counting
            }
            long seq;
            if (ts == lastTs) {
                if (lastSeq == SnowflakeLayout.MAX_SEQUENCE) {
                    waiter.pause(); // out of sequence numbers: wait for the clock, never reuse or borrow
                    continue;
                }
                seq = lastSeq + 1;
            } else {
                seq = 0;
            }
            if (ts > SnowflakeLayout.MAX_TIMESTAMP) {
                throw new IllegalStateException("timestamp bits exhausted: epoch is more than 2^41 ms old");
            }
            long next = (ts << SEQUENCE_BITS) | seq;
            beforeCas.run();
            if (state.compareAndSet(previous, next)) {
                return SnowflakeLayout.compose(ts, node, (int) seq);
            }
        }
    }
}
