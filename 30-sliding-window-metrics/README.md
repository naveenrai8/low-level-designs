# Topic 30: Sliding-Window Metrics

"Show me requests per second and p99 latency for the last 60 seconds" sounds like a loop over a list. The interviewer is waiting for what breaks at scale: you cannot keep every sample, old samples must disappear on time, many threads write at once, and p99 cannot be averaged across servers.

A staff answer uses a ring of time slices with a fixed-size histogram in each, says exactly when a time slice is stale, proves the percentile error with a measurement, and bounds the number of series.

## Table of Contents

0. [Words used in this topic](#0-words-used-in-this-topic)
1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Window** | "The last N seconds". A sample older than that no longer counts. |
| **Time slice** | One slice of time (window / B). It holds a count, a sum and a histogram for the samples that arrived in that slice. |
| **Histogram bin** | One value range inside a histogram, for example "values 1000 to 1015". Not the same thing as a time slice. |
| **Epoch** | The number of a slice: floor(now / slice length). Epoch 6 means "the 7th slice since time zero". |
| **Ring** | B slots (one per time slice) used round and round. Epoch e lives in slot `e mod B`. Each slot remembers which epoch it holds. |
| **Percentile** | p99 is the value that 99% of samples are at or below. |
| **Cardinality** | How many different keys (series) exist. Each key costs a whole ring of histograms. |

---

## 1. The Problem

### What breaks without one

Keeping every sample in a list uses unbounded memory and needs a sort for each percentile. Keeping one running counter cannot forget old data. A single average hides the slow requests that users actually feel.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| What is measured? | Non-negative `long` values (for example microseconds): count, sum, rate, p50/p99/p999. |
| How exact? | Counts exact. Percentiles within 0.78% relative error. |
| Window? | Fixed at creation, for example 60 s in 12 time slices of 5 s. |
| Time? | Injected `Ticker`. No real time anywhere in the logic. |
| Empty window? | Counters are 0. A percentile is `OptionalLong.empty()`, never a fake 0. |

**Out of scope:** an exact sliding window (ours moves in slice-sized steps, see Gaps), HTTP serving, and exemplars.

---

## 2. How It Solves It

### Worked example first: window 10 s, 5 time slices of 2 s

Requests arrive at t = 1, 3, 3, 9 and 12 seconds. A slice is 2 s long, so epoch = floor(t / 2) and slot = epoch mod 5.

| Time | Event | Epoch | Slot | What the ring holds afterwards |
|---|---|---|---|---|
| t=1 | request | 0 | 0 | slot0 = (epoch 0, 1 sample) |
| t=3 | 2 requests | 1 | 1 | slot1 = (epoch 1, 2 samples) |
| t=9 | request | 4 | 4 | slot4 = (epoch 4, 1 sample) |
| t=9 | count() | now is epoch 4, live epochs are 0..4 | | all three slots are live, count = 4 |
| t=12 | request | 6 | 1 | slot1 held epoch 1 (an older lap), so it is reset and becomes (epoch 6, 1 sample) |
| t=12 | count() | now is epoch 6, live epochs are 2..6 | | slot0 (epoch 0) is stale, slot1 (epoch 6) and slot4 (epoch 4) are live, so count = 2 |

At t=12 the answer is 2: the t=9 and t=12 requests. The two t=3 requests are only 9 s old, yet they are gone, because their whole time slice (epoch 1, covering 2..4 s) aged out when epoch 6 began. An exact 10 s window would still count them and return 4. Nobody looped over slots to clear anything: slot 0 is ignored because epoch 0 is not in 2..6, and slot 1 was reset only because a writer needed it.

The rule: a slot is live when `current - B < slot.epoch <= current`. So the window is the (B-1) newest full slices plus the current partial one. A sample is forgotten between (B-1)·b and B·b after it was recorded (here 8 to 10 s), so it can be dropped up to one slice early, never late.

| Case | What happens |
|---|---|
| Boundary tick | A sample at t=1 is in epoch 0, live until epoch 5 begins at t=10. It is dropped at age 9 s. Test: `exactBoundaryTick` checks 10 s minus 1 ns and 10 s, and `sampleIsDroppedAtAgeNineSecondsNotTen`. |
| Gap of exactly B slices | Everything is stale and the next write reuses a slot cleanly. Test: `gapOfExactlyBSlices`. |
| Gap of B-1 slices | The oldest sample is still alive. Test: `gapOfBMinusOneSlicesKeepsOldest`. |
| Idle for a year | A read does zero reset work (it takes B short locks). The next write resets one slot. Resets are never a loop over skipped slices, so the work is at most B per lap. Test: `longIdleGapExpiresEverythingWithConstantWork` checks the reset counter. |
| Negative `nanoTime` | Floor division keeps epochs correct. Test: `negativeTickerReadingsWork`. |

The epoch number is what makes a "lazily read slot from an older lap" safe. Without it you would need a background thread to clear slots on time, and a stalled thread would show old data as new.

### Entities and invariants

| Entity | Invariant |
|---|---|
| Slice (one slot of the ring) | `count == sum(bins)`; slot index == `floorMod(epoch, B)`; all fields change together under the slice's lock. |
| Live set | A slice is live iff `cur - B < epoch <= cur`. Readers only look at live slices. |
| Lifetime totals | `lifetimeCount` and `lifetimeSum` only go up, even when a late sample is dropped from the window. |
| `KeyedMetrics` | At most `maxKeys` own series plus the one `__other__` series, so memory is bounded. |

```
 KeyedMetrics --1..maxKeys--> RingWindowedMetric --B--> Slice (epoch, count, sum, bins[2241])
      |                              |  implements WindowedMetric        ^ LogLinear maps value -> bin
      | snapshotAll()                | snapshot()
      v                              v
 RegistrySnapshot ---> MetricSnapshot (immutable, merge)  ---> Exporter (InMemory, PrometheusText)

 record(v):  epoch = floorDiv(now, sliceLen); slot = ring[epoch mod B]      <- "pick"
             lock slot:  slot.epoch > epoch ? drop  : (slot.epoch < epoch ? reset); then count++, sum+=v, bins[bin(v)]++
 count():    cur = floorDiv(now, sliceLen); for each slot: lock; if live add count; unlock
 percentile: snapshot() adds the bins of live slots, then walks the merged bins to rank = ceil(q * total)
```

Cost: `record` is O(1), plus on the first write of a lap a reset of 2241 longs (about 18 KB) under that slot's lock. `count`/`sum` are O(B). `percentile`/`snapshot` are B x 2241 adds (about 27k at B=12) plus a walk of 2241 bins.

### The histogram (log-linear, HdrHistogram style)

Values 0..127 each get their own bin (exact). From 128 up, each power-of-two range is cut into 64 equal bins. A bin is never wider than 1/64 of its smallest value, and we report its middle, so the worst relative error is 1/128 = 0.78%. See `LogLinear`. Range tracked: up to 2^40 (about 18 minutes in nanoseconds). Larger values go to the top bin and lose the bound. The layout has 2241 bins, so one ring of 12 time slices is about 215 KB (computed: 2241 x 8 bytes x 12) per series. That is why cardinality matters (F2).

A percentile adds the live slices' bins, then walks the merged array until it has covered rank = ceil(q x total) samples ("nearest rank"; a tiny tolerance stops 0.28 x 25 = 7.000000000000001 from becoming rank 8).

### Measured percentile error

Setup: `PercentileAccuracyTest`, 200,000 seeded samples per distribution, window 60 s in 12 time slices spread over 20 time steps, exact answer from the sorted raw samples with the same rank rule, Apple laptop, JDK 21.0.8, deterministic (seeded). The test asserts error <= 1/128 = 0.78%.

| Distribution | p50 error | p99 error | p999 error |
|---|---|---|---|
| Uniform 1..1,000,000 | 0.328% | 0.308% | 0.381% |
| Lognormal (mu 9, sigma 1.2) | 0.062% | 0.216% | 0.150% |
| Exponential (mean 5000) | 0.260% | 0.426% | 0.141% |
| Bimodal (95% near 2,000, 5% near 400,000) | 0.248% | 0.278% | 0.195% |

All are inside the bound. The worst case needs a value at the edge of a wide bin, so the bound (0.78%) is about twice what these runs hit.

### The interface and its error contract

```java
public interface WindowedMetric {
    void record(long value);            // IllegalArgumentException if value < 0
    long count();                       // 0 on empty
    long sum();                         // 0 on empty, saturates at Long.MAX_VALUE
    double rate();                      // samples per second over the B-1 newest full slices
    OptionalLong percentile(double q);  // IllegalArgumentException if q is NaN or outside 0..1; empty if no samples
    MetricSnapshot snapshot();          // immutable, mergeable
}
```

### The concurrency model

| Question | Answer |
|---|---|
| Shared state | Per time slice: epoch, count, sum and the bins. Plus lifetime totals (a `LongAdder` and an atomic sum). |
| Invariant | A sample is written into the slice of the epoch it was picked for, or dropped from the window. It never lands in a slice reset for a newer lap. |
| Primitive | One `synchronized` lock per slice guards all four fields together. |
| Why not striped `LongAdder` counters? | Count and sum could be adders, but the epoch check, the reset and the histogram must change together. Splitting them brings back the race. One lock per slice is simple, writers only collide inside the same time slice, and readers take B short locks one at a time. |
| When would adders win? | A single hot key with millions of writes per second per core. Then stripe the histogram too (one per core, merge on read). That is the production delta below. |

Inside the lock the writer compares epochs. Slot older than mine: reset, then write. Slot equal: write. Slot newer (my thread was paused for a whole lap): my sample is already older than the window, so drop it from the window, count it in `droppedLate()`, and still add it to the lifetime totals.

Forced races (no sleep), all with `Gap` hooks and latches in `ConcurrencyTest`:

- A writer is frozen between "pick slice" and "write" while time moves. The stale 1000 does not leak into the new lap. A writer paused for less than a lap lands in its own slice: the test then advances to t=10 and checks that the 1000 expired with epoch 0.
- A reset is parked between relabelling the slot and clearing it. `count()`, `sum()` and `snapshot()` on other threads must reach `BLOCKED` on the slot lock, then see only the finished state. Removing the read locks fails this test.

### Code and tests

| File | Role |
|---|---|
| [`WindowedMetric`](src/main/java/com/lld/metrics/WindowedMetric.java) | Interface and error contract. |
| [`RingWindowedMetric`](src/main/java/com/lld/metrics/RingWindowedMetric.java) | The ring, epochs and per-slice locks. |
| [`LogLinear`](src/main/java/com/lld/metrics/LogLinear.java) | Histogram bin layout, error bound, quantile walk. |
| [`MetricSnapshot`](src/main/java/com/lld/metrics/MetricSnapshot.java) | Immutable copy with `merge`. |
| [`KeyedMetrics`](src/main/java/com/lld/metrics/KeyedMetrics.java) | F2 registry with a key cap and the `__other__` series (a user key literally named `__other__` is merged into it). |
| [`Exporter`](src/main/java/com/lld/metrics/Exporter.java), [`InMemoryExporter`](src/main/java/com/lld/metrics/InMemoryExporter.java), [`PrometheusTextExporter`](src/main/java/com/lld/metrics/PrometheusTextExporter.java) | F3 seam. |
| `WindowRotationTest`, `MetricContractTest`, `LogLinearTest` | Boundaries, idle gaps, error contract and stable `rate()`, bin layout and rank rule. |
| `DifferentialTest`, `PercentileAccuracyTest`, `ConcurrencyTest` | Naive-model comparison over 20 seeds, the error table, forced races and exact-total hammers. |
| `MergeTest`, `KeyedMetricsTest`, `ExporterTest` | F1, F2, F3. |

---

## 3. The Gaps

### F1: Mergeable sketches

The layout is fixed, so two histograms merge by adding bins. `MergeTest.mergeEqualsOneInstanceFedBothStreams` feeds two hosts and one combined instance and asserts the merged bin counts are exactly equal.

Why you must not average p99s: host A serves 10,000 requests, all at 10 ms, so its p99 is 10 ms. Host B serves 10 requests, all at 1000 ms, so its p99 is 1000 ms. The average of the two is 505 ms. The real fleet has 10,010 requests and only 10 are slow, so the true p99 is 10 ms (99% of 10,010 is 9,910 requests, all fast). The average is wrong by 50x because it ignores how many requests each host served. Merge the histograms, then ask for the percentile. Test: `averagingP99sIsWrong`. Merging also rejects snapshots with a different window length or slice count.

| Sketch | Guarantee | Merge | Pick it when |
|---|---|---|---|
| HdrHistogram (ours is the same idea, simplified) | Fixed relative error (set by significant digits), fixed range, fixed memory | Exact add | Latency in a known range and you want fast, predictable recording. |
| t-digest (Dunning and Ertl) | Very accurate near the tails (p99, p999), size grows with a compression setting, error is not a fixed bound | Merge is lossy but stays accurate | Unknown or huge value range, interested in extreme quantiles, offline or streaming aggregation. |
| DDSketch | Relative-error guarantee on any quantile, with a bounded bin count, and merges keep the guarantee | Exact add | You want a proven relative error without a fixed range. Used in Datadog. |

Not coded for t-digest and DDSketch: only discussion, see the papers in References.

### F2: Per-key cardinality bound

`KeyedMetrics` allows at most `maxKeys` own series. Later keys go to one `__other__` series. Count is exact, memory is bounded by `maxKeys + 1` rings. With 215 KB per ring (computed above), 1000 keys is about 215 MB. Plan the cap from that.

Locking: a key that has a series is found lock-free. A new key takes the registration lock once. Once the cap is full the map never changes (no eviction), so an overflow lookup returns `__other__` without the lock too; only the filling phase locks. Tests: cap and exact overflow counts, the reserved name, an overflow lookup returns while another thread holds the registration lock, a forced first-registration race (a `Gap` parks two threads after their lock-free miss) gives one instance, and 8 threads racing over 200 keys never exceed the cap and lose nothing. Cost of this design: you cannot tell how many distinct keys overflowed, and the first keys in win, not the busiest.

### F3: Exporter seam

`Exporter.export(RegistrySnapshot)` receives an immutable copy, so a slow exporter cannot block writers. `InMemoryExporter` is for tests. `PrometheusTextExporter` writes the text exposition format as a summary: windowed quantile lines, plus `_sum` and `_count`, which are the lifetime (cumulative) totals because Prometheus reads a drop as a counter reset and `rate(x_count[5m])` would spike. Test: `_count` and `_sum` stay put after every sample has expired. Metric names are checked against `[a-zA-Z_:][a-zA-Z0-9_:]*` and label values are escaped.

### Misconceptions and common mistakes

| Mistake | Why wrong |
|---|---|
| "Loop and clear the skipped slices on rotation" | After a long idle gap this does a huge amount of work, or leaves old data in. Use an epoch per slot and ignore stale ones. |
| "Average the p99s" | Shown above. Merge histograms. |
| "A fresh empty window has p99 = 0" | A fake 0 looks like a great latency and hides an outage. Return empty. |
| "Divide by the whole window for the rate" | Right after a slice boundary the count covers only (B-1) slices plus a sliver, so the rate dips by up to 1/B (20% at B=5, 8% at B=12) and alerts flap. We divide the (B-1) full slices by their length instead. |
| "Use `System.currentTimeMillis` for the window" | It can jump back. Use a monotonic ticker. |

### Trade-offs and when not to use

The window moves in slice-sized steps: the effective window is the (B-1) newest full slices plus the current partial one, so a sample can vanish up to one slice early (never late). More slices mean a smoother edge and more memory. `rate()` uses only the (B-1) full slices, so it is stable but lags by the partial slice; `count()` includes it, so `rate() x window` can differ from `count()`. For billing or exact counts use a cumulative counter, not a window. The injected-time idea is in [Topic 10](../10-time-and-testability/); the rate limiter's [sliding-window counter](../21-rate-limiter/) interpolates two windows instead of keeping B slices.

### Production and distributed delta

- Stripe per core and merge on read when one key is very hot.
- Ship cumulative histograms (bin counters that only go up) to Prometheus, and let it compute quantiles with `histogram_quantile` or merge sketches in a pipeline.
- Hosts must use the same histogram layout and window, or merging is meaningless (the code rejects different windows or slice counts).
- Wider context and the planned emission-path guides: [Observability-poc ideas](../../pocs/Observability-poc/ideas.md), and why percentiles cannot be averaged in [SystemDesign-poc 61 tail latency](../../pocs/SystemDesign-poc/61-tail-latency/README.md).

---

## 4. Official References

- HdrHistogram: [site](http://hdrhistogram.org/) and [GitHub](https://github.com/HdrHistogram/HdrHistogram).
- Ted Dunning and Otmar Ertl, [Computing Extremely Accurate Quantiles Using t-Digests](https://arxiv.org/abs/1902.04023), and the [t-digest code](https://github.com/tdunning/t-digest).
- Masson, Rim, Lee, [DDSketch: A Fast and Fully-Mergeable Quantile Sketch with Relative-Error Guarantees](https://arxiv.org/abs/1908.10693), and the [Java implementation](https://github.com/DataDog/sketches-java).
- Prometheus: [histograms and summaries best practices](https://prometheus.io/docs/practices/histograms/), [metric types](https://prometheus.io/docs/concepts/metric_types/), [exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/).
- [Micrometer distribution summaries](https://docs.micrometer.io/micrometer/reference/concepts/distribution-summaries.html) and [Dropwizard Metrics core manual](https://metrics.dropwizard.io/4.2.0/manual/core.html) (a real library you would use instead of writing this).

---

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Ask the window, the error you can accept, how many keys, one host or a fleet. |
| 5-12 | Draw the ring, do the epoch example on the board, state the live rule. |
| 12-22 | Write the interface and error contract, then `record` and the stale check. |
| 22-30 | Histogram: why not raw samples, log-linear layout, the error bound. |
| 30-37 | Concurrency: per-slice lock, the paused-writer race. |
| 37-42 | Fleet: merge histograms, never average p99s. Key cap with `__other__`. |
| 42-45 | Tests: fake ticker, boundary tick, long gap, measured error. |

---

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Window | List of timestamps or a ring of slices | Ring with an epoch per slot, no clearing loop, boundary tick tested to the nanosecond, can say the window is (B-1) slices plus a partial |
| Idle gap | Loops over skipped slices | Constant work, gap of B and B-1 tested |
| Percentiles | Sorts samples | Fixed histogram with a stated and measured error bound |
| Empty window and rate | Returns 0, divides by the whole window | Returns empty, and a rate that does not saw-tooth |
| Concurrency | `synchronized` on the whole thing | Per-slice lock, paused-writer and half-done-reset races forced by hooks, exact hammer totals |
| Cardinality | Ignores | Hard cap with an exact overflow series, a lock-free overflow path and a memory estimate |

---

## 7. Key Takeaways

1. A window is a ring of slices. Each slot stores its epoch, and a reader ignores any slot whose epoch is out of range.
2. Never loop to clear skipped slices. Reset only the slot a writer needs.
3. A fixed log-linear histogram gives bounded memory and bounded relative error (here 0.78%, measured at 0.43% at most).
4. Percentiles do not average. Histograms with the same layout merge exactly. Prometheus `_sum` and `_count` must be cumulative.
5. A writer paused between "pick" and "write", and a reset parked half way, are the races to test; hooks make them deterministic.
6. Bound the keys. Memory is series x ring x histogram, and the overflow series keeps counts exact.
7. Hand exporters an immutable snapshot.
8. `rate()` divides by the span actually covered, or it saw-tooths.

**Self-grade: STRONG.** Full spine, forced rotation races, exact boundaries, measured error table, differential test. Gaps: the Prometheus text is checked by string assertions, not a parser, t-digest and DDSketch are discussion only, and the hot-key striped variant is described, not benchmarked.

[← Topic 29: Consistent Hashing](../29-consistent-hashing/) · [Index](../README.md) · [Topic 31: Seat-Hold Booking →](../31-seat-hold-booking/)
