package com.lld.popularity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bounded-memory version: a ring of Count-Min sketches, one per bucket, each with a small heap of that bucket's heaviest items.
 *
 * <p>Writing: the event goes into the current bucket's sketch, then the item's new estimate is offered to that bucket's heap. Reading: the candidates are the union of the live buckets' heap ids; each candidate's window count is the sum of its estimates in the live buckets (a sum of overestimates, so still never low). Expiry: a bucket that leaves the window is simply not read, and is cleared before it is reused.
 *
 * <p>Memory is fixed: {@link #counterBytes()} for the tables plus at most {@code buckets * heapSize} heap entries. Known limit: an item that is never in the top {@code heapSize} of any single bucket, but is heavy over the whole window, can be missed. Raise {@code heapSize} or use shorter buckets to cut that risk.
 */
public final class SketchWindowedTopK implements TopK {

    private final Ticker ticker;
    private final WindowSpec spec;
    private final long[] epoch;
    private final CountMinSketch[] sketches;
    private final IndexedMinHeap[] heaps;

    /** Creates a tracker whose sketches are sized from {@code eps} and {@code delta}, with a per-bucket heap of {@code heapSize}, all hashing from {@code seed}. */
    public SketchWindowedTopK(Ticker ticker, WindowSpec spec, double eps, double delta, int heapSize, long seed) {
        this.ticker = ticker;
        this.spec = spec;
        this.epoch = new long[spec.buckets()];
        Arrays.fill(epoch, Long.MIN_VALUE);
        this.sketches = new CountMinSketch[spec.buckets()];
        this.heaps = new IndexedMinHeap[spec.buckets()];
        for (int i = 0; i < spec.buckets(); i++) {
            sketches[i] = new CountMinSketch(eps, delta, seed);
            heaps[i] = new IndexedMinHeap(heapSize);
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
            sketches[slot].clear();
            heaps[slot].clear();
            epoch[slot] = idx;
        }
        sketches[slot].add(item, 1);
        heaps[slot].offer(item, sketches[slot].estimate(item));
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<Ranked> topK(int k) {
        long cur = spec.indexOf(ticker.read());
        Set<String> candidates = new HashSet<>();
        for (int s = 0; s < epoch.length; s++) {
            if (isLiveSlot(s, cur)) {
                candidates.addAll(heaps[s].idList());
            }
        }
        Map<String, Long> est = new HashMap<>();
        for (String id : candidates) {
            est.put(id, estimateAt(id, cur));
        }
        return Ranked.top(est, k);
    }

    /** Returns the estimated window count of one item: never below the true count. */
    public synchronized long estimate(String item) {
        return estimateAt(item, spec.indexOf(ticker.read()));
    }

    /** Returns the bytes used by all counter tables: {@code buckets * depth * width * 8}. Heap entries are extra and bounded by {@code buckets * heapSize}. */
    public long counterBytes() {
        return sketches[0].counterBytes() * sketches.length;
    }

    /** Sums an item's estimates over the live buckets. */
    private long estimateAt(String id, long cur) {
        long sum = 0;
        for (int s = 0; s < epoch.length; s++) {
            if (isLiveSlot(s, cur)) {
                sum += sketches[s].estimate(id);
            }
        }
        return sum;
    }

    /** True when the slot holds a bucket that is still inside the window. */
    private boolean isLiveSlot(int s, long cur) {
        return epoch[s] != Long.MIN_VALUE && spec.isLive(epoch[s], cur);
    }
}
