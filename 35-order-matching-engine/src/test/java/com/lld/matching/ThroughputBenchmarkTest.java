package com.lld.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** An opt-in measurement, off by default. Run it with {@code -Dmatching.bench=true}. It reads {@code System.nanoTime}, so it measures this toy; it is not a correctness test and not a benchmark of a real exchange. */
@EnabledIfSystemProperty(named = "matching.bench", matches = "true")
@Timeout(120)
class ThroughputBenchmarkTest {

    /** Times a seeded 500,000-command flow on a bare book and through the queue and engine thread, after warm-up runs, and prints orders per second. */
    @Test
    void ordersPerSecond() throws Exception {
        List<Command> flow = Flow.random(42, 500_000);
        System.out.println("flow: 500000 seeded commands (60% limit, 8% IOC, 6% FOK, 6% market, 20% cancel/replace)");
        for (int i = 0; i < 3; i++) {
            direct(flow);
            engine(flow);
        }
        List<Double> d = new ArrayList<>();
        List<Double> q = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            d.add(flow.size() / direct(flow));
            q.add(flow.size() / engine(flow));
        }
        Collections.sort(d);
        Collections.sort(q);
        System.out.printf("direct book, one thread: median %.0f cmds/s (min %.0f, max %.0f)%n", d.get(2), d.get(0), d.get(4));
        System.out.printf("via queue + engine thread, 1 producer: median %.0f cmds/s (min %.0f, max %.0f)%n", q.get(2), q.get(0), q.get(4));
    }

    /** Applies the whole flow to a fresh book on this thread and returns elapsed seconds. */
    private static double direct(List<Command> flow) {
        LimitOrderBook b = new LimitOrderBook();
        long t0 = System.nanoTime();
        for (Command c : flow) {
            b.apply(c);
        }
        return (System.nanoTime() - t0) / 1e9;
    }

    /** Submits the whole flow through a fresh engine, waits for the last result, and returns elapsed seconds. */
    private static double engine(List<Command> flow) throws Exception {
        MatchingEngine e = new MatchingEngine(4096).start();
        long t0 = System.nanoTime();
        java.util.concurrent.CompletableFuture<Result> last = null;
        for (Command c : flow) {
            last = e.submit(c);
        }
        last.get();
        double s = (System.nanoTime() - t0) / 1e9;
        e.close();
        return s;
    }
}
