package com.lld.idgen;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** An opt-in benchmark, off by default. Run it with {@code -Didgen.bench=true}. It reads the real clock, so it is a measurement, not a correctness test. */
@EnabledIfSystemProperty(named = "idgen.bench", matches = "true")
class ThroughputBenchmarkTest {

    /** Prints IDs per second for 1 and 8 threads sharing one generator, three rounds each. The per-node cap is 4096 IDs per millisecond, about 4.1 million per second. */
    @Test
    @Timeout(120)
    void idsPerSecond() throws Exception {
        for (int round = 0; round < 3; round++) {
            for (int threads : new int[] {1, 8}) {
                SnowflakeIdGenerator g = new SnowflakeIdGenerator(Clock.systemUTC(), Waiter.spin(), 1, 1_700_000_000_000L, 1_000);
                int per = 2_000_000;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                long start = System.nanoTime();
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    fs.add(pool.submit(() -> {
                        for (int i = 0; i < per; i++) {
                            g.nextId();
                        }
                    }));
                }
                for (Future<?> f : fs) {
                    f.get();
                }
                long nanos = System.nanoTime() - start;
                pool.shutdown();
                System.out.printf("BENCH threads=%d ids/sec=%d%n", threads, (long) ((double) threads * per / nanos * 1e9));
            }
        }
    }
}
