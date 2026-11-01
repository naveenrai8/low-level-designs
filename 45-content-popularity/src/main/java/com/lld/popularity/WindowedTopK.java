package com.lld.popularity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact version: one count map per bucket in a ring, summed on read.
 *
 * <p>Memory grows with the number of distinct items in the window, so this is the correct baseline, not the production answer for huge catalogues (see {@link SketchWindowedTopK}).
 *
 * <p>Bucket rotation and the stale-bucket reset are explained in Topic 30. Here the rule is: before a slot is written, if the bucket number it holds is not the one being written, wipe it first.
 */
public final class WindowedTopK implements TopK {

    private final Ticker ticker;
    private final WindowSpec spec;
    private final long[] epoch;
    private final List<Map<String, Long>> maps = new ArrayList<>();

    /** Creates an empty tracker whose time comes from {@code ticker}. */
    public WindowedTopK(Ticker ticker, WindowSpec spec) {
        this.ticker = ticker;
        this.spec = spec;
        this.epoch = new long[spec.buckets()];
        Arrays.fill(epoch, Long.MIN_VALUE);
        for (int i = 0; i < spec.buckets(); i++) {
            maps.add(new HashMap<>());
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void record(String item) {
        recordAt(item, ticker.read());
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean recordAt(String item, long atNanos) {
        Validation.item(item);
        long now = ticker.read();
        if (atNanos > now) {
            throw new IllegalArgumentException("event is in the future");
        }
        long cur = spec.indexOf(now);
        long idx = spec.indexOf(atNanos);
        if (!spec.isLive(idx, cur)) {
            return false;
        }
        int slot = spec.slotOf(idx);
        if (epoch[slot] != idx) {
            maps.get(slot).clear();
            epoch[slot] = idx;
        }
        maps.get(slot).merge(item, 1L, Long::sum);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<Ranked> topK(int k) {
        return Ranked.top(totals(), k);
    }

    /** Returns the count of one item over the live buckets, 0 when unseen. */
    public synchronized long count(String item) {
        return totals().getOrDefault(item, 0L);
    }

    /** Sums the live buckets into one map. Slots whose bucket number is out of the window are skipped, even if they were never reused. */
    private Map<String, Long> totals() {
        long cur = spec.indexOf(ticker.read());
        Map<String, Long> sum = new HashMap<>();
        for (int s = 0; s < epoch.length; s++) {
            if (epoch[s] != Long.MIN_VALUE && spec.isLive(epoch[s], cur)) {
                maps.get(s).forEach((id, c) -> sum.merge(id, c, Long::sum));
            }
        }
        return sum;
    }
}
