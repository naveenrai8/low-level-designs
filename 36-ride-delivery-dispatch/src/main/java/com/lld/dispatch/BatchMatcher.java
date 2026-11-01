package com.lld.dispatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.LongFunction;

/**
 * Turns a batch of requests plus the grid of available drivers into a request-to-driver map, in two phases so the slow part can run outside any lock.
 * <p>Phase 1, {@link #gather}: for each request ask the grid for its nearest allowed drivers (at most {@link #CANDIDATES_PER_REQUEST}, and never more than the batch size) and take the union as the candidate columns. A snapshot of each candidate's details is copied, so phase 2 needs no access to the live fleet.
 * <p>Phase 2, {@link #solve}: score every pair with the strategy and let the assigner choose.
 * <p>Why the cut-off is safe: in a batch of n, a request can lose at most n-1 of its nearest drivers to the others, so n candidates each are enough for an exact answer with DISTANCE scoring. With the cap of 8, the answer is exact for batches of 8 or fewer and a good approximation above that (only the 8 nearest are tried). Also, with rating or ETA scoring the optimum might use a driver outside the nearest few, so those results are good, not provably optimal.
 */
public final class BatchMatcher {

    /** The most requests handled in one batch; keeps the cost sums far below the FORBIDDEN marker. */
    public static final int MAX_BATCH = 256;

    /** The most candidate drivers fetched per request; bounds the work done while holding the fleet lock. */
    public static final int CANDIDATES_PER_REQUEST = 8;

    /**
     * The candidate drivers and a frozen copy of their details.
     *
     * @param ids the candidate driver ids, in a fixed order
     * @param infos the details of each candidate, same order
     */
    public record Candidates(List<Long> ids, List<DriverInfo> infos) {
    }

    /** Not instantiable: static helpers only. */
    private BatchMatcher() {
    }

    /**
     * Phase 1: picks candidate drivers from the grid. Run it while holding whatever lock protects the grid. Changes nothing.
     *
     * @param batch the requests, at most {@link #MAX_BATCH}
     * @param index the available drivers only
     * @param info looks up a driver's details by id
     * @param allowed false for a pair that must not be used (the driver already declined this request)
     * @return the candidates with a snapshot of their details
     */
    public static Candidates gather(List<Request> batch, DriverIndex index, LongFunction<DriverInfo> info, BiPredicate<Request, Long> allowed) {
        if (batch.size() > MAX_BATCH) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "batch larger than " + MAX_BATCH);
        }
        int k = Math.min(batch.size(), CANDIDATES_PER_REQUEST);
        Set<Long> cand = new LinkedHashSet<>();
        for (Request r : batch) {
            Set<Long> taken = new LinkedHashSet<>();
            for (int i = 0; i < k; i++) {
                var m = index.nearest(r.pickup(), id -> !taken.contains(id) && allowed.test(r, id));
                if (m.isEmpty()) {
                    break;
                }
                taken.add(m.get().driverId());
            }
            cand.addAll(taken);
        }
        List<Long> ids = new ArrayList<>(cand);
        List<DriverInfo> infos = new ArrayList<>();
        for (long id : ids) {
            infos.add(info.apply(id));
        }
        return new Candidates(ids, infos);
    }

    /**
     * Phase 2: scores and assigns. Needs no lock because it only reads the snapshot. Throws BAD_COST before returning anything if a score is out of range.
     *
     * @param batch the same requests given to {@link #gather}
     * @param c the candidates from {@link #gather}
     * @param allowed false for a forbidden pair
     * @param scoring the cost of a pair
     * @param assigner greedy or optimal
     * @return request id to driver id, in batch order; requests that got nobody are absent
     */
    public static Map<Long, Long> solve(List<Request> batch, Candidates c, BiPredicate<Request, Long> allowed, ScoringStrategy scoring, BatchAssigner assigner) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (batch.isEmpty() || c.ids().isEmpty()) {
            return result;
        }
        int n = batch.size();
        long[][] cost = new long[n][c.ids().size()];
        for (int i = 0; i < n; i++) {
            Request r = batch.get(i);
            for (int j = 0; j < c.ids().size(); j++) {
                long id = c.ids().get(j);
                if (!allowed.test(r, id)) {
                    cost[i][j] = BatchAssigner.FORBIDDEN;
                    continue;
                }
                long v = scoring.cost(r.pickup(), c.infos().get(j));
                if (v < 0 || v > ScoringStrategy.MAX_COST) {
                    throw new DispatchException(DispatchException.Code.BAD_COST, "cost " + v + " for request " + r.id() + " and driver " + id);
                }
                cost[i][j] = v;
            }
        }
        int[] pick = assigner.assign(cost);
        for (int i = 0; i < n; i++) {
            if (pick[i] >= 0) {
                result.put(batch.get(i).id(), c.ids().get(pick[i]));
            }
        }
        return result;
    }

    /**
     * Both phases in one call, for callers that hold the lock for the whole thing (tests and small uses).
     *
     * @param batch the requests, at most {@link #MAX_BATCH}
     * @param index the available drivers only
     * @param info looks up a driver's details by id
     * @param allowed false for a pair that must not be used
     * @param scoring the cost of a pair
     * @param assigner greedy or optimal
     * @return request id to driver id; requests that got nobody are absent
     */
    public static Map<Long, Long> match(List<Request> batch, DriverIndex index, LongFunction<DriverInfo> info, BiPredicate<Request, Long> allowed, ScoringStrategy scoring, BatchAssigner assigner) {
        return solve(batch, gather(batch, index, info, allowed), allowed, scoring, assigner);
    }
}
