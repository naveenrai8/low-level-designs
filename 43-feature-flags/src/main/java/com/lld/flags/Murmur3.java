package com.lld.flags;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 (x86, 32-bit), written out by hand so the hash is the same on every JVM and every release.
 * <p>We do not use {@code String.hashCode}: its formula is specified, but it mixes poorly on keys like user-1 and user-2, and it works on Java UTF-16 chars so only Java can reproduce it. Every SDK language must compute the same bucket, and Murmur3 has standard ports.
 * <p>Murmur3 is not a secure hash. That is fine here, because the goal is an even spread, not secrecy.
 */
public final class Murmur3 {
    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    /** Not instantiable: this class only holds a static function. */
    private Murmur3() {
    }

    /**
     * Hashes the UTF-8 bytes of the text with seed 0.
     *
     * @param text the text to hash
     * @return the 32-bit hash (may be negative as a Java int; treat it as unsigned)
     */
    public static int hash32(String text) {
        return hash32(text.getBytes(StandardCharsets.UTF_8), 0);
    }

    /**
     * Hashes raw bytes with a seed, following the reference algorithm step by step.
     *
     * @param data the bytes to hash
     * @param seed the seed (0 gives the published test vectors)
     * @return the 32-bit hash
     */
    public static int hash32(byte[] data, int seed) {
        int h = seed;
        int len = data.length;
        int i = 0;
        // Body: mix in four bytes at a time, little-endian.
        for (; i + 4 <= len; i += 4) {
            int k = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) | ((data[i + 2] & 0xff) << 16) | ((data[i + 3] & 0xff) << 24);
            h ^= mixK(k);
            h = Integer.rotateLeft(h, 13);
            h = h * 5 + 0xe6546b64;
        }
        // Tail: the last 0 to 3 bytes.
        int tail = len & 3;
        int k = 0;
        if (tail == 3) {
            k ^= (data[i + 2] & 0xff) << 16;
        }
        if (tail >= 2) {
            k ^= (data[i + 1] & 0xff) << 8;
        }
        if (tail >= 1) {
            k ^= data[i] & 0xff;
            h ^= mixK(k);
        }
        h ^= len;
        return fmix(h);
    }

    /**
     * Scrambles one 4-byte block before it is folded into the running hash.
     *
     * @param k the block
     * @return the scrambled block
     */
    private static int mixK(int k) {
        k *= C1;
        k = Integer.rotateLeft(k, 15);
        return k * C2;
    }

    /**
     * Final avalanche step: makes every input bit affect every output bit.
     *
     * @param h the running hash
     * @return the finished hash
     */
    private static int fmix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
