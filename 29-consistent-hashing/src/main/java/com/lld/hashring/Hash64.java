package com.lld.hashring;

import java.nio.charset.StandardCharsets;

/**
 * A seeded 64-bit hash: MurmurHash3 x64 128-bit, keeping the first 64 bits.
 *
 * <p>A hash ring needs a hash that spreads keys evenly and gives the same answer on every machine and every run. {@code String.hashCode()} fails both tests: it is 32 bits, clusters badly for similar strings, and is not specified for this use. MurmurHash3 is a published algorithm with published test vectors, so it can be checked.
 *
 * <p>{@code String.hashCode()} is deterministic (its formula is specified), so that is not its flaw. It fails on width (32 bits) and spread: similar strings give clustered values, which piles keys onto a few ring arcs.
 *
 * <p>Seed note: the reference algorithm takes a 32-bit unsigned seed and Guava a 32-bit int. Seeds outside [0, 2^31) here are not interoperable with those implementations.
 *
 * <p>The seed lets two rings (or two tests) use different but repeatable layouts.
 */
public final class Hash64 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    /** Not instantiable: all methods are static. */
    private Hash64() {
    }

    /** Hashes the UTF-8 bytes of the text and returns the first 64 bits of MurmurHash3 x64 128. */
    public static long hash(String text, long seed) {
        return hash(text.getBytes(StandardCharsets.UTF_8), seed);
    }

    /** Hashes the bytes and returns the first 64 bits of MurmurHash3 x64 128. */
    public static long hash(byte[] data, long seed) {
        return murmur3x64(data, seed)[0];
    }

    /** Returns both 64-bit halves {h1, h2} of MurmurHash3 x64 128, so tests can compare against the published 128-bit vectors. */
    public static long[] murmur3x64(byte[] data, long seed) {
        long h1 = seed;
        long h2 = seed;
        int len = data.length;
        int blocks = len / 16;
        for (int b = 0; b < blocks; b++) {
            long k1 = readLong(data, b * 16);
            long k2 = readLong(data, b * 16 + 8);
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729L;
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5L;
        }
        int tail = blocks * 16;
        long k1 = 0;
        long k2 = 0;
        int rest = len & 15;
        for (int i = rest - 1; i >= 8; i--) {
            k2 ^= (data[tail + i] & 0xffL) << ((i - 8) * 8);
        }
        if (rest > 8) {
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
        }
        for (int i = Math.min(rest, 8) - 1; i >= 0; i--) {
            k1 ^= (data[tail + i] & 0xffL) << (i * 8);
        }
        if (rest > 0) {
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
        }
        h1 ^= len;
        h2 ^= len;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;
        return new long[] {h1, h2};
    }

    /** The Murmur finalizer: mixes all 64 bits so that a one-bit change in the input flips about half the output bits. Also used to combine two hashes in rendezvous hashing. */
    public static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /** Reads 8 bytes starting at the offset as a little-endian long. */
    private static long readLong(byte[] d, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (d[off + i] & 0xffL);
        }
        return v;
    }
}
