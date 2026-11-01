package com.lld.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the hash is the published one, buckets are stable and independent, rollouts only grow, and the spread is even.
 */
class BucketingTest {
    private static final int USERS = 100_000;

    /**
     * Pins the hash to published MurmurHash3 x86_32 answers, so a changed hash (which would move every user) fails here.
     */
    @Test
    void murmur3MatchesPublishedAnswers() {
        assertEquals(0, Murmur3.hash32(new byte[0], 0));
        assertEquals(0x514E28B7, Murmur3.hash32(new byte[0], 1));
        assertEquals(0x248bfa47, Murmur3.hash32("hello"));
        assertEquals(0xb3dd93fa, Murmur3.hash32("abc"));
        assertEquals(0xba6bd213, Murmur3.hash32("test"));
        assertEquals(0x2e4ff723, Murmur3.hash32("The quick brown fox jumps over the lazy dog"));
        assertEquals(0x5a97808a, Murmur3.hash32("aaaa".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0x9747b28c));
    }

    /**
     * Pins a few real buckets so the same user lands in the same bucket on every JVM and release.
     */
    @Test
    void bucketsArePinnedAcrossRuns() {
        assertEquals(PIN_A, Bucketing.bucket("checkout", "s1", "user-1"));
        assertEquals(PIN_B, Bucketing.bucket("checkout", "s1", "user-2"));
        assertEquals(PIN_C, Bucketing.bucket("search", "", "user-1"));
    }

    private static final int PIN_A = 5021;
    private static final int PIN_B = 5955;
    private static final int PIN_C = 8135;

    /**
     * The same inputs always give the same bucket, and it is always inside 0..9999.
     */
    @Test
    void sameInputsSameBucketInRange() {
        for (int i = 0; i < 5_000; i++) {
            int b = Bucketing.bucket("f", "s", "u" + i);
            assertEquals(b, Bucketing.bucket("f", "s", "u" + i));
            assertTrue(b >= 0 && b < Bucketing.BUCKETS);
        }
    }

    /**
     * The flag key is part of the hash, so two flags at 10% overlap by about 1% (10% of 10%), not by 10%.
     */
    @Test
    void flagsAreIndependent() {
        int both = 0;
        for (int i = 0; i < USERS; i++) {
            boolean a = Bucketing.bucket("flag-a", "", "u" + i) < 1000;
            boolean b = Bucketing.bucket("flag-b", "", "u" + i) < 1000;
            if (a && b) {
                both++;
            }
        }
        // Independent: expect 1,000 of 100,000. Fully correlated would be 10,000.
        assertTrue(both > 800 && both < 1200, "overlap was " + both);
    }

    /**
     * Changing the salt moves users: most users change bucket.
     */
    @Test
    void saltReshufflesUsers() {
        int same = 0;
        for (int i = 0; i < 1000; i++) {
            if (Bucketing.bucket("f", "s1", "u" + i) == Bucketing.bucket("f", "s2", "u" + i)) {
                same++;
            }
        }
        assertTrue(same < 10, "same=" + same);
    }

    /**
     * Joining the parts with a separator means ("ab","c") and ("a","bc") are different inputs.
     */
    @Test
    void partsDoNotRunTogether() {
        assertNotEquals(Bucketing.bucket("ab", "c", "u"), Bucketing.bucket("a", "bc", "u"));
    }

    /**
     * Raising a rollout from 10% to 20% keeps everyone who was in, and adds only new people.
     */
    @Test
    void rolloutIsMonotonic() {
        InMemoryFeatureFlags at10 = Fixtures.with(1, Fixtures.pct("f", 1000));
        InMemoryFeatureFlags at20 = Fixtures.with(1, Fixtures.pct("f", 2000));
        int in10 = 0;
        int in20 = 0;
        for (int i = 0; i < USERS; i++) {
            boolean a = at10.evaluate("f", Context.of("u" + i), "off").variant().equals("on");
            boolean b = at20.evaluate("f", Context.of("u" + i), "off").variant().equals("on");
            if (a) {
                in10++;
                assertTrue(b, "user u" + i + " was in at 10% but out at 20%");
            }
            if (b) {
                in20++;
            }
        }
        assertTrue(in20 > in10);
    }

    /**
     * 0% gives nobody and 100% gives everybody.
     */
    @Test
    void zeroAndHundredPercent() {
        InMemoryFeatureFlags none = Fixtures.with(1, Fixtures.pct("f", 0));
        InMemoryFeatureFlags all = Fixtures.with(1, Fixtures.pct("f", 10_000));
        for (int i = 0; i < 20_000; i++) {
            assertEquals("off", none.evaluate("f", Context.of("u" + i), "x").variant());
            assertEquals("on", all.evaluate("f", Context.of("u" + i), "x").variant());
        }
    }

    /**
     * The edges of a slice: with 1,000 basis points, bucket 999 is in and bucket 1000 is out; 1 basis point is exactly bucket 0.
     */
    @Test
    void sliceEdges() {
        Rollout ten = Rollout.percent("on", "off", 1000);
        assertEquals("on", ten.variantFor(0));
        assertEquals("on", ten.variantFor(999));
        assertEquals("off", ten.variantFor(1000));
        assertEquals("off", ten.variantFor(9999));
        Rollout one = Rollout.percent("on", "off", 1);
        assertEquals("on", one.variantFor(0));
        assertEquals("off", one.variantFor(1));
        assertThrows(IllegalArgumentException.class, () -> ten.variantFor(10_000));
        assertThrows(InvalidConfigException.class, () -> Rollout.percent("on", "off", 10_001));
    }

    /**
     * A three-way A/B/C split must add up to exactly 10,000; 9,999 or 10,001 is refused, and every bucket gets exactly one variant.
     */
    @Test
    void weightsMustSumToBucketCount() {
        assertThrows(InvalidConfigException.class, () -> new Rollout(List.of(new Rollout.Slice("a", 3333), new Rollout.Slice("b", 3333), new Rollout.Slice("c", 3333))));
        assertThrows(InvalidConfigException.class, () -> new Rollout(List.of(new Rollout.Slice("a", 5000), new Rollout.Slice("b", 5001))));
        Rollout abc = new Rollout(List.of(new Rollout.Slice("a", 3334), new Rollout.Slice("b", 3333), new Rollout.Slice("c", 3333)));
        int[] counts = new int[3];
        for (int b = 0; b < Bucketing.BUCKETS; b++) {
            counts[abc.variantFor(b).charAt(0) - 'a']++;
        }
        assertEquals(3334, counts[0]);
        assertEquals(3333, counts[1]);
        assertEquals(3333, counts[2]);
    }

    /**
     * Measures the spread for 100,000 users: observed share at 10% and 50%, and a chi-square over 100 equal bins (99 degrees of freedom, 0.1% critical value 148.2).
     */
    @Test
    void distributionIsEven() {
        int[] bins = new int[100];
        int in10 = 0;
        int in50 = 0;
        for (int i = 0; i < USERS; i++) {
            int b = Bucketing.bucket("checkout", "s1", "user-" + i);
            bins[b / 100]++;
            if (b < 1000) {
                in10++;
            }
            if (b < 5000) {
                in50++;
            }
        }
        double expected = USERS / 100.0;
        double chi = 0;
        for (int c : bins) {
            chi += (c - expected) * (c - expected) / expected;
        }
        System.out.printf("MEASURED 10%%=%.4f 50%%=%.4f chi2(99df)=%.1f%n", in10 / (double) USERS, in50 / (double) USERS, chi);
        assertTrue(Math.abs(in10 / (double) USERS - 0.10) < 0.005);
        assertTrue(Math.abs(in50 / (double) USERS - 0.50) < 0.005);
        assertTrue(chi < 148.2, "chi2=" + chi);
    }
}
