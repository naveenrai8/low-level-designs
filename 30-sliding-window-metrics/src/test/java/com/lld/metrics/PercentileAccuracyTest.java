package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/** Measures the percentile error against exact answers from sorted raw samples, and asserts it stays inside the stated bound. */
class PercentileAccuracyTest {

    private static final int SAMPLES = 200_000;
    private static final double[] QS = {0.5, 0.99, 0.999};

    /** Feeds a seeded stream, compares p50/p99/p999 with the exact sorted answer, prints a table row and asserts the bound. */
    private void check(String name, LongSupplier source) {
        FakeTicker ticker = new FakeTicker();
        RingWindowedMetric metric = new RingWindowedMetric(ticker, Duration.ofSeconds(60), 12);
        long[] raw = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            raw[i] = source.getAsLong();
            metric.record(raw[i]);
            if (i % 10_000 == 9_999) {
                ticker.advanceSeconds(1);
            }
        }
        Arrays.sort(raw);
        StringBuilder row = new StringBuilder("MEASURED " + name);
        for (double q : QS) {
            long exact = raw[(int) LogLinear.rank(q, SAMPLES) - 1];
            long got = metric.percentile(q).getAsLong();
            double err = exact == 0 ? 0 : Math.abs(got - exact) / (double) exact;
            row.append(String.format(" p%s exact=%d got=%d err=%.4f%%", q, exact, got, err * 100));
            assertTrue(err <= LogLinear.MAX_RELATIVE_ERROR, name + " q=" + q + " err=" + err);
        }
        System.out.println(row);
    }

    /** Uniform values from 1 to 1,000,000. */
    @Test
    void uniform() {
        Random r = new Random(11);
        check("uniform", () -> 1 + r.nextInt(1_000_000));
    }

    /** Lognormal values (a typical latency shape with a long tail). */
    @Test
    void lognormal() {
        Random r = new Random(12);
        check("lognormal", () -> (long) Math.exp(9 + 1.2 * r.nextGaussian()));
    }

    /** Exponential values with mean 5,000. */
    @Test
    void exponential() {
        Random r = new Random(13);
        check("exponential", () -> (long) (-5000 * Math.log(1 - r.nextDouble())));
    }

    /** Bimodal: 95 percent fast (about 2,000) and 5 percent slow (about 400,000), so p99 and p999 sit in the slow mode. */
    @Test
    void bimodal() {
        Random r = new Random(14);
        check("bimodal", () -> r.nextDouble() < 0.95 ? (long) (2000 + 200 * r.nextGaussian()) : (long) (400_000 + 40_000 * r.nextGaussian()));
    }
}
