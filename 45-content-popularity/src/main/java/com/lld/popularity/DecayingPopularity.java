package com.lld.popularity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Popularity where recent events count more than old ones: every event's weight halves each {@code halfLife}.
 *
 * <p>The lazy trick: instead of shrinking every score as time passes, make new events bigger. An event at time t is stored as {@code weight * 2^((t - ref) / halfLife)}. To read, multiply the stored sum by {@code 2^(-(now - ref) / halfLife)}. Every item shares the same read factor, so the stored numbers already sort in the right order and a write touches one entry.
 *
 * <p>The catch: the stored numbers grow without limit and would reach infinity after about 1000 half-lives (infinity times the read factor 0 then gives NaN). So when {@code now - ref} passes {@value #MAX_HALF_LIVES} half-lives, everything is rescaled to a new {@code ref = now} (one pass), and entries that shrank below {@value #PRUNE_BELOW} are dropped. An idle gap of any length therefore ends in a clean zero, never NaN.
 */
public final class DecayingPopularity {

    /** How many half-lives the reference time may lag before the stored values are rescaled. 2^64 is far below the double limit of 2^1023. */
    public static final int MAX_HALF_LIVES = 64;

    /** Smallest event weight accepted. With weights at least this big, a fresh event is never below {@link #PRUNE_BELOW} and so never pruned while fresh. */
    public static final double MIN_WEIGHT = 1e-6;

    /** Largest event weight accepted. A stored value can be up to weight * 2^65 before a rebase, and 1e250 * 2^65 is still far below the double limit of about 1.8e308. */
    public static final double MAX_WEIGHT = 1e250;

    /** Scores below this are treated as gone when rescaling, which bounds memory for items that stopped trending. */
    public static final double PRUNE_BELOW = 1e-12;

    private final Ticker ticker;
    private final double halfLifeNanos;
    private final Map<String, Double> stored = new HashMap<>();
    private long refNanos;

    /** Creates an empty tracker with the given half-life (must be positive). The reference time starts at the ticker's current reading. */
    public DecayingPopularity(Ticker ticker, long halfLifeNanos) {
        if (halfLifeNanos <= 0) {
            throw new IllegalArgumentException("halfLife must be > 0");
        }
        this.ticker = ticker;
        this.halfLifeNanos = halfLifeNanos;
        this.refNanos = ticker.read();
    }

    /** Adds one event of weight 1 at the current time. */
    public void record(String item) {
        record(item, 1.0);
    }

    /** Adds one event of the given weight at the current time. The weight must be in [{@link #MIN_WEIGHT}, {@link #MAX_WEIGHT}] or {@link IllegalArgumentException} is thrown. */
    public synchronized void record(String item, double weight) {
        Validation.item(item);
        if (!(weight >= MIN_WEIGHT && weight <= MAX_WEIGHT)) {
            throw new IllegalArgumentException("weight must be in [1e-6, 1e250]");
        }
        long now = ticker.read();
        if ((now - refNanos) / halfLifeNanos > MAX_HALF_LIVES) {
            rebase(now);
        }
        stored.merge(item, weight * Math.pow(2.0, (now - refNanos) / halfLifeNanos), Double::sum);
    }

    /** Returns the decayed score of one item right now, 0 when unseen. */
    public synchronized double score(String item) {
        Double s = stored.get(item);
        return s == null ? 0.0 : s * readFactor(ticker.read());
    }

    /** Returns the best {@code k} items by decayed score, hottest first; ties go to the smaller id. */
    public synchronized List<Scored> topK(int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0");
        }
        double f = readFactor(ticker.read());
        List<Scored> all = new ArrayList<>();
        stored.forEach((id, v) -> all.add(new Scored(id, v * f)));
        all.sort((a, b) -> {
            int c = Double.compare(b.score(), a.score());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        return k >= all.size() ? all : new ArrayList<>(all.subList(0, k));
    }

    /** Returns how many items are stored. Pruning keeps this from growing forever. */
    public synchronized int size() {
        return stored.size();
    }

    /** The factor that turns a stored number into a score at time {@code now}. */
    private double readFactor(long now) {
        return Math.pow(2.0, -(now - refNanos) / halfLifeNanos);
    }

    /** Moves the reference time to {@code now}: scales every stored value down by what has decayed since, and drops the ones that are effectively zero. */
    private void rebase(long now) {
        double f = readFactor(now);
        stored.replaceAll((id, v) -> v * f);
        stored.values().removeIf(v -> v < PRUNE_BELOW);
        refNanos = now;
    }
}
