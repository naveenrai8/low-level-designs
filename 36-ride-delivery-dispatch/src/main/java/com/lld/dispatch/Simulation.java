package com.lld.dispatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A seeded virtual-time experiment: how far does the assigned driver have to drive to the rider under different dispatch policies?
 * <p>World: a square of {@code side} metres, {@code drivers} drivers at random spots, requests arriving at random times and places, one-second ticks. A driver who is assigned accepts at once, drives the pickup, and is busy {@code tripSeconds} before reappearing at a random place. Same seed, same world, so policies are compared on identical demand.
 * <p>Measured values: the straight-line pickup distance (rounded up to whole metres) from the driver's position at assignment time to the rider, and the rider wait (request to offer). Offers are noticed at the end of each one-second tick, so waits have 1 s resolution.
 * <p>Note: with a window of 0 a finished trip calls {@code dispatchPending} at once (that is what immediate dispatch means). With a window above 0 it does not: waiting requests are picked up by the next window flush, so the window is respected.
 */
public final class Simulation {

    /**
     * Summary of one run.
     *
     * @param assigned how many requests got a driver
     * @param meanMetres the mean pickup distance
     * @param p95Metres the 95th percentile pickup distance (nearest-rank)
     * @param totalRequests how many requests were made
     * @param meanWaitMs the mean time from request to offer, measured at one-second tick resolution
     * @param p95WaitMs the 95th percentile of that wait (nearest-rank)
     */
    public record Result(int assigned, long meanMetres, long p95Metres, int totalRequests, long meanWaitMs, long p95WaitMs) {
    }

    /** Not instantiable: static helpers only. */
    private Simulation() {
    }

    /**
     * Runs one policy over one seeded world.
     *
     * @param seed the random seed
     * @param drivers number of drivers
     * @param requests number of requests
     * @param horizonSeconds requests arrive uniformly in the first this many seconds
     * @param tripSeconds how long an assigned driver is busy
     * @param side the side of the square world in metres
     * @param windowMs batching window, 0 for immediate
     * @param assigner greedy or optimal
     * @return pickup-distance statistics
     */
    public static Result run(long seed, int drivers, int requests, int horizonSeconds, int tripSeconds, long side, long windowMs, BatchAssigner assigner) {
        Random rnd = new Random(seed);
        ManualScheduler clock = new ManualScheduler();
        Fleet fleet = new Fleet(1000, clock::nowMs);
        Dispatcher disp = new Dispatcher(fleet, clock, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, assigner, windowMs, 60_000, 1000);
        for (int i = 1; i <= drivers; i++) {
            fleet.register(i, new Point(0, 0), 50, 10);
            fleet.goOnline(i, randomPoint(rnd, side));
        }
        int[] arrival = new int[requests];
        Point[] spot = new Point[requests];
        for (int i = 0; i < requests; i++) {
            arrival[i] = rnd.nextInt(horizonSeconds);
            spot[i] = randomPoint(rnd, side);
        }
        Integer[] order = new Integer[requests];
        for (int i = 0; i < requests; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> arrival[a] != arrival[b] ? Integer.compare(arrival[a], arrival[b]) : Integer.compare(a, b));
        List<Long> open = new ArrayList<>();
        List<Long> dists = new ArrayList<>();
        List<Long> waits = new ArrayList<>();
        int next = 0;
        for (int sec = 0; dists.size() < requests && sec < horizonSeconds + 100_000; sec++) {
            while (next < requests && arrival[order[next]] <= sec) {
                int k = order[next++];
                disp.submit(new Request(k + 1, spot[k]));
                open.add((long) (k + 1));
            }
            open = collect(open, disp, fleet, clock, spot, arrival, dists, waits, tripSeconds, side, rnd, windowMs);
            clock.advance(1000);
            open = collect(open, disp, fleet, clock, spot, arrival, dists, waits, tripSeconds, side, rnd, windowMs);
        }
        int n = dists.size();
        return new Result(n, mean(dists), p95(dists), requests, mean(waits), p95(waits));
    }

    /**
     * Accepts every request that has just been offered (recording its distance and wait) and starts its trip; returns the requests still waiting.
     */
    private static List<Long> collect(List<Long> open, Dispatcher disp, Fleet fleet, ManualScheduler clock, Point[] spot, int[] arrival, List<Long> dists, List<Long> waits, int tripSeconds, long side, Random rnd, long windowMs) {
        List<Long> still = new ArrayList<>();
        for (long rid : open) {
            if (disp.status(rid) == Dispatcher.Status.OFFERED) {
                long d = disp.driverOf(rid);
                dists.add(Geometry.isqrtCeil(fleet.info(d).where().distSq(spot[(int) rid - 1])));
                waits.add(clock.nowMs() - arrival[(int) rid - 1] * 1000L);
                disp.accept(disp.offerOf(rid));
                fleet.startTrip(d);
                Point drop = randomPoint(rnd, side);
                clock.schedule((long) tripSeconds * 1000, () -> {
                    fleet.completeTrip(d, drop);
                    if (windowMs == 0) {
                        disp.dispatchPending();
                    }
                });
            } else {
                still.add(rid);
            }
        }
        return still;
    }

    /** Mean of a list, rounded down; 0 for an empty list. */
    private static long mean(List<Long> l) {
        long sum = 0;
        for (long v : l) {
            sum += v;
        }
        return l.isEmpty() ? 0 : sum / l.size();
    }

    /** The 95th percentile by the nearest-rank method (the value at position ceil(0.95 n) of the sorted list); 0 for an empty list. */
    private static long p95(List<Long> l) {
        List<Long> sorted = new ArrayList<>(l);
        java.util.Collections.sort(sorted);
        return sorted.isEmpty() ? 0 : sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
    }

    /** A uniformly random point in the square [0, side) on both axes. */
    private static Point randomPoint(Random rnd, long side) {
        return new Point((long) (rnd.nextDouble() * side), (long) (rnd.nextDouble() * side));
    }
}
