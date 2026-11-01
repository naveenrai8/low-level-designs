package com.lld.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One {@link RingWindowedMetric} per key (for example per endpoint) with a hard cap on how many distinct keys get their own series.
 *
 * <p>Why: every new key allocates a whole ring of histograms. If the key comes from user input (a URL with an id in it), an attacker or a bug can create millions of series and use up all memory. This is called a cardinality explosion.
 *
 * <p>Rule: the first {@code maxKeys} distinct keys get their own series. Any later key is counted in the single {@value #OVERFLOW_KEY} series, so no sample is lost and the total memory is bounded by {@code maxKeys + 1} series. We cannot count how many distinct keys overflowed without remembering them, which is the cost we refuse to pay. A caller who uses the literal key {@value #OVERFLOW_KEY} is merged into the overflow series on purpose.
 *
 * <p>Locking: a key that already has a series is found lock-free. A new key takes the registration lock once, to create its series. Once the cap is full the registry never changes again (keys are never evicted), so an overflow lookup also returns without the lock. Only the brief period while the cap is still filling takes the lock.
 */
public final class KeyedMetrics {

    /** The name of the shared series that receives every key that arrives after the cap is full. */
    public static final String OVERFLOW_KEY = "__other__";

    private final Ticker ticker;
    private final Duration window;
    private final int slices;
    private final int maxKeys;
    private final ConcurrentHashMap<String, RingWindowedMetric> series = new ConcurrentHashMap<>();
    private final RingWindowedMetric overflow;
    private final Object registerLock = new Object();
    private final AtomicLong lockEntries = new AtomicLong();
    private final Gap missGap;

    /** Creates a registry. All series share the window and slice count. {@code maxKeys} must be at least 1. */
    public KeyedMetrics(Ticker ticker, Duration window, int slices, int maxKeys) {
        this(ticker, window, slices, maxKeys, Gap.NONE);
    }

    /** Same as the public constructor, plus a test hook that runs after a lock-free miss and before the registration lock is taken. */
    KeyedMetrics(Ticker ticker, Duration window, int slices, int maxKeys, Gap missGap) {
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys must be at least 1");
        }
        this.ticker = ticker;
        this.window = window;
        this.slices = slices;
        this.maxKeys = maxKeys;
        this.missGap = missGap;
        this.overflow = new RingWindowedMetric(ticker, window, slices);
    }

    /**
     * Returns the series for {@code key}: its own if it has one or there is room for one, otherwise the overflow series.
     *
     * <p>Asking for the key {@value #OVERFLOW_KEY} returns the overflow series and never uses up a slot. Throws {@link IllegalArgumentException} for a null key.
     */
    public WindowedMetric metric(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (key.equals(OVERFLOW_KEY)) {
            return overflow;
        }
        RingWindowedMetric existing = series.get(key);
        if (existing != null) {
            return existing;
        }
        if (series.size() >= maxKeys) {
            return overflow;
        }
        missGap.pause();
        synchronized (registerLock) {
            lockEntries.incrementAndGet();
            existing = series.get(key);
            if (existing != null) {
                return existing;
            }
            if (series.size() >= maxKeys) {
                return overflow;
            }
            RingWindowedMetric created = new RingWindowedMetric(ticker, window, slices);
            series.put(key, created);
            return created;
        }
    }

    /** Records a sample under {@code key}, routing to the overflow series when the cap is full. */
    public void record(String key, long value) {
        metric(key).record(value);
    }

    /** Returns how many keys have their own series (never more than maxKeys; the overflow series is not counted). */
    public int size() {
        return series.size();
    }

    /** Returns how many times the registration lock was taken. A test uses it to prove that overflow lookups stay lock-free. */
    long registrationLockEntries() {
        return lockEntries.get();
    }

    /** Returns the registration lock so a test can hold it from another thread. */
    Object registrationLockForTest() {
        return registerLock;
    }

    /** Takes an immutable picture of every series, plus the overflow series, which is always present under its fixed key. */
    public RegistrySnapshot snapshotAll() {
        Map<String, MetricSnapshot> all = new TreeMap<>();
        for (Map.Entry<String, RingWindowedMetric> e : series.entrySet()) {
            all.put(e.getKey(), e.getValue().snapshot());
        }
        all.put(OVERFLOW_KEY, overflow.snapshot());
        return new RegistrySnapshot(ticker.read(), all);
    }

    /** Takes a snapshot and hands it to the exporter. */
    public void exportTo(Exporter exporter) {
        exporter.export(snapshotAll());
    }
}
