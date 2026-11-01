package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Checks the ring against a naive model that keeps every sample with its time slice and filters on read. */
class DifferentialTest {

    /** One remembered sample in the naive model. */
    private record Sample(long epoch, long value) {
    }

    /** Runs random records and random time jumps (some far longer than the window) and compares count, sum and percentiles at every step. */
    @Test
    void ringMatchesNaiveModel() {
        for (int seed = 0; seed < 20; seed++) {
            Random r = new Random(seed);
            FakeTicker ticker = new FakeTicker(-123_456_789L);
            int buckets = 2 + r.nextInt(7);
            long bucketMs = 100;
            RingWindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofMillis(bucketMs * buckets), buckets);
            List<Sample> model = new ArrayList<>();
            for (int step = 0; step < 400; step++) {
                int action = r.nextInt(10);
                if (action < 6) {
                    long v = r.nextInt(5000);
                    metric.record(v);
                    model.add(new Sample(Math.floorDiv(ticker.read(), bucketMs * 1_000_000L), v));
                } else if (action < 9) {
                    ticker.advanceMillis(r.nextInt(250));
                } else {
                    ticker.advanceMillis(bucketMs * buckets * (1 + r.nextInt(3)) + r.nextInt(100));
                }
                compare(metric, model, Math.floorDiv(ticker.read(), bucketMs * 1_000_000L), buckets);
            }
        }
    }

    /** Computes the expected answers from the raw list and asserts the ring agrees. */
    private void compare(RingWindowedMetric metric, List<Sample> model, long now, int buckets) {
        List<Long> live = new ArrayList<>();
        long sum = 0;
        for (Sample s : model) {
            if (s.epoch() > now - buckets) {
                live.add(s.value());
                sum += s.value();
            }
        }
        live.sort(Long::compare);
        assertEquals(live.size(), metric.count());
        assertEquals(sum, metric.sum());
        if (live.isEmpty()) {
            assertEquals(false, metric.percentile(0.5).isPresent());
            return;
        }
        for (double q : new double[] {0.0, 0.5, 0.9, 0.99, 1.0}) {
            long exact = live.get((int) LogLinear.rank(q, live.size()) - 1);
            assertEquals(LogLinear.representative(LogLinear.indexOf(exact)), metric.percentile(q).getAsLong());
        }
    }
}
