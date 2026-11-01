package com.lld.idgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Proves the bit layout: field widths, the sign bit, round trips, and how long 41 timestamp bits last. */
class SnowflakeLayoutTest {

    /** The three field widths plus the sign bit add up to exactly 64. */
    @Test
    void widthsAddUpTo64() {
        assertEquals(63, SnowflakeLayout.TIMESTAMP_BITS + SnowflakeLayout.NODE_BITS + SnowflakeLayout.SEQUENCE_BITS);
    }

    /** The biggest value in every field still gives a positive long, so the sign bit is never touched. */
    @Test
    void maxFieldsKeepSignBitClear() {
        long id = SnowflakeLayout.compose(SnowflakeLayout.MAX_TIMESTAMP, SnowflakeLayout.MAX_NODE, SnowflakeLayout.MAX_SEQUENCE);
        assertEquals(Long.MAX_VALUE, id);
    }

    /** Each field lands at its documented bit position. */
    @Test
    void fieldsSitAtDocumentedPositions() {
        assertEquals(1L << 22, SnowflakeLayout.compose(1, 0, 0));
        assertEquals(1L << 12, SnowflakeLayout.compose(0, 1, 0));
        assertEquals(1L, SnowflakeLayout.compose(0, 0, 1));
    }

    /** Packing then unpacking gives back the same three numbers, for 10,000 seeded random triples. */
    @Test
    void composeThenDecodeRoundTrips() {
        Random r = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            long ts = r.nextLong(SnowflakeLayout.MAX_TIMESTAMP + 1);
            int node = r.nextInt(SnowflakeLayout.MAX_NODE + 1);
            int seq = r.nextInt(SnowflakeLayout.MAX_SEQUENCE + 1);
            long id = SnowflakeLayout.compose(ts, node, seq);
            assertEquals(ts, SnowflakeLayout.timestamp(id));
            assertEquals(node, SnowflakeLayout.node(id));
            assertEquals(seq, SnowflakeLayout.sequence(id));
        }
    }

    /** A bigger timestamp always beats a bigger node or sequence, because time is the highest field. */
    @Test
    void timeDominatesOrdering() {
        long earlier = SnowflakeLayout.compose(5, SnowflakeLayout.MAX_NODE, SnowflakeLayout.MAX_SEQUENCE);
        long later = SnowflakeLayout.compose(6, 0, 0);
        assertTrue(earlier < later);
    }

    /** Out-of-range fields are rejected instead of silently spilling into the next field. */
    @Test
    void outOfRangeFieldsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnowflakeLayout.compose(SnowflakeLayout.MAX_TIMESTAMP + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeLayout.compose(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeLayout.compose(0, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> SnowflakeLayout.compose(0, 0, 4096));
    }

    /** Timestamp exhaustion: 2^41 ms is 2,199,023,255,552 ms, which is 69.7 years (using a 365.25-day year). */
    @Test
    void timestampBitsLastAbout69Years() {
        long totalMillis = SnowflakeLayout.MAX_TIMESTAMP + 1;
        assertEquals(2_199_023_255_552L, totalMillis);
        double years = totalMillis / (365.25 * 24 * 3600 * 1000);
        assertEquals(69.7, years, 0.05);
    }

    /** Converting back to Unix time adds the epoch to the timestamp field. */
    @Test
    void unixMillisAddsEpoch() {
        long id = SnowflakeLayout.compose(1234, 1, 1);
        assertEquals(1_700_000_001_234L, SnowflakeLayout.unixMillis(id, 1_700_000_000_000L));
    }
}
