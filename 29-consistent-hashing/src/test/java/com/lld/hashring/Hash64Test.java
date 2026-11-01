package com.lld.hashring;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Proves the hash is the real MurmurHash3 x64 128 (published vectors) and behaves like a good ring hash. */
class Hash64Test {

    /** The empty input with seed 0 hashes to all zeros, a published MurmurHash3 x64 128 vector. */
    @Test
    void emptyInputVector() {
        assertArrayEquals(new long[] {0L, 0L}, Hash64.murmur3x64(new byte[0], 0));
    }

    /** The classic "quick brown fox" sentence with seed 0 gives the published 128-bit value e34bbc7bbc071b6c7a433ca9c49a9347. */
    @Test
    void quickBrownFoxVector() {
        byte[] data = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        long[] h = Hash64.murmur3x64(data, 0);
        assertEquals(0xe34bbc7bbc071b6cL, h[0]);
        assertEquals(0x7a433ca9c49a9347L, h[1]);
    }

    /** A 1-byte input (tail only), a 16-byte input (one full block, no tail) and a 29-byte input (block plus a tail longer than 8 bytes) match values from my own Python port, which an independent reviewer port also reproduced (together with the Guava vectors below). */
    @Test
    void blockAndTailPaths() {
        assertEquals(0x85555565f6597889L, Hash64.hash("a", 0));
        assertEquals(0x4be06d94cf4ad1a7L, Hash64.hash("0123456789abcdef", 0));
        assertEquals(0x01a327805d9623c4L, Hash64.hash("0123456789abcdefXYZ0123456789", 0));
    }

    /** Vectors from Guava's Murmur3Hash128Test, with non-zero seeds and tails of 4 to 9 bytes, each as (h1, h2). */
    @Test
    void guavaVectorsWithSeeds() {
        Object[][] v = {
            {0, "hell", 0x629942693e10f867L, 0x92db0b82baeb5347L},
            {1, "hello", 0xa78ddff5adae8d10L, 0x128900ef20900135L},
            {2, "hello ", 0x8a486b23f422e826L, 0xf962a2c58947765fL},
            {3, "hello w", 0x2ea59f466f6bed8cL, 0xc610990acc428a17L},
            {4, "hello wo", 0x79f6305a386c572cL, 0x46305aed3483b94eL},
            {5, "hello wor", 0xc2219d213ec1f1b5L, 0xa1d8e2e0a52785bdL},
            {0, "The quick brown fox jumps over the lazy cog", 0x658ca970ff85269aL, 0x43fee3eaa68e5c3eL},
        };
        for (Object[] row : v) {
            long[] h = Hash64.murmur3x64(((String) row[1]).getBytes(StandardCharsets.UTF_8), (Integer) row[0]);
            assertEquals((long) (Long) row[2], h[0], "h1 for " + row[1]);
            assertEquals((long) (Long) row[3], h[1], "h2 for " + row[1]);
        }
    }

    /** The same input and seed always give the same value, and a different seed gives a different value. */
    @Test
    void deterministicAndSeeded() {
        assertEquals(Hash64.hash("node-1#0", 7), Hash64.hash("node-1#0", 7));
        assertNotEquals(Hash64.hash("node-1#0", 7), Hash64.hash("node-1#0", 8));
    }

    /** Flipping one input character flips about half of the 64 output bits on average (the avalanche property that String.hashCode lacks). */
    @Test
    void avalancheIsRoughlyHalf() {
        long flipped = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            flipped += Long.bitCount(Hash64.hash("key-" + i, 1) ^ Hash64.hash("kez-" + i, 1));
        }
        double avg = flipped / (double) n;
        assertTrue(avg > 28 && avg < 36, "average flipped bits " + avg);
    }
}
