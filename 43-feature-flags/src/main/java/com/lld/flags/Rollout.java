package com.lld.flags;

import java.util.List;

/**
 * Splits the 10,000 buckets between variants, in order. Weights are in buckets (basis points), so 1,000 means 10%.
 * <p>Example: [on 1000, off 9000] puts buckets 0..999 on "on" and 1000..9999 on "off". Raising "on" to 2000 only moves the line, so everyone who was in stays in.
 *
 * @param slices the variants and their weights, in bucket order; the weights must add up to exactly {@link Bucketing#BUCKETS}
 */
public record Rollout(List<Slice> slices) {

    /**
     * One variant's share of the buckets.
     *
     * @param variant the variant name
     * @param weight how many buckets (0 or more)
     */
    public record Slice(String variant, int weight) {
        /**
         * Rejects a blank variant or a negative weight.
         */
        public Slice {
            if (variant == null || variant.isBlank() || weight < 0) {
                throw new InvalidConfigException("slice needs a variant and a weight of 0 or more");
            }
        }
    }

    /**
     * Copies the slices and checks the weights add up to exactly 10,000, so no bucket is left without a variant and none is counted twice.
     */
    public Rollout {
        if (slices == null || slices.isEmpty()) {
            throw new InvalidConfigException("rollout needs at least one slice");
        }
        slices = List.copyOf(slices);
        long sum = 0;
        for (Slice s : slices) {
            sum += s.weight();
        }
        if (sum != Bucketing.BUCKETS) {
            throw new InvalidConfigException("rollout weights add up to " + sum + ", must be exactly " + Bucketing.BUCKETS);
        }
    }

    /**
     * Builds the common two-way split: the first basisPoints buckets get "on", the rest get "off".
     *
     * @param on the variant for users inside the rollout
     * @param off the variant for everyone else
     * @param basisPoints 0 (nobody) to 10,000 (everybody); 100 is one percent
     * @return the rollout
     */
    public static Rollout percent(String on, String off, int basisPoints) {
        if (basisPoints < 0 || basisPoints > Bucketing.BUCKETS) {
            throw new InvalidConfigException("basis points must be 0.." + Bucketing.BUCKETS + " but was " + basisPoints);
        }
        return new Rollout(List.of(new Slice(on, basisPoints), new Slice(off, Bucketing.BUCKETS - basisPoints)));
    }

    /**
     * Finds which variant owns a bucket by walking the slices and adding up their weights.
     *
     * @param bucket a bucket from 0 to 9,999
     * @return the variant for that bucket
     */
    public String variantFor(int bucket) {
        if (bucket < 0 || bucket >= Bucketing.BUCKETS) {
            throw new IllegalArgumentException("bucket out of range: " + bucket);
        }
        int end = 0;
        for (Slice s : slices) {
            end += s.weight();
            if (bucket < end) {
                return s.variant();
            }
        }
        throw new IllegalStateException("weights did not cover bucket " + bucket);
    }
}
