package com.lld.flags;

/**
 * Turns (flag key, salt, user id) into a bucket number from 0 to 9,999.
 * <p>A bucket is a slot. Every user always lands in the same slot for the same flag, so "10% of users" means "buckets 0 to 999" and is stable between calls, servers and restarts.
 * <p>There are 10,000 buckets so the finest step is 0.01 percent (one basis point).
 */
public final class Bucketing {
    /** How many buckets there are. A rollout of N basis points covers buckets 0 to N-1. */
    public static final int BUCKETS = 10_000;

    /** Not instantiable: this class only holds a static function. */
    private Bucketing() {
    }

    /**
     * Gives the bucket for one user and one flag.
     * <p>The text hashed is flagKey, salt and user id joined by a NUL character, so ("ab","c") and ("a","bc") cannot collide by accident. Because the flag key is part of the text, two flags put the same user in unrelated buckets. Changing the salt re-shuffles everyone for that flag.
     * <p>The 32-bit hash is read as unsigned and reduced with a modulo. The modulo leaves a tiny bias (2^32 is not a multiple of 10,000), each of the lowest 7,296 buckets gets 429,497 hash values and the rest get 429,496 (about 1 in 430,000), far below anything measurable here.
     *
     * @param flagKey the flag's key
     * @param salt an extra string to re-shuffle users (may be empty)
     * @param userId the user's stable id
     * @return a number from 0 to {@link #BUCKETS} minus 1
     */
    public static int bucket(String flagKey, String salt, String userId) {
        int hash = Murmur3.hash32(flagKey + '\0' + salt + '\0' + userId);
        return (int) (Integer.toUnsignedLong(hash) % BUCKETS);
    }
}
