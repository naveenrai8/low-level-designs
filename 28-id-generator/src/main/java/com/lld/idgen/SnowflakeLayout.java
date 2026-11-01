package com.lld.idgen;

/**
 * The bit layout of a Snowflake-style ID, with helpers to pack and unpack it.
 *
 * <pre>
 *  63 | 62 ........ 22 | 21 ...... 12 | 11 ....... 0
 *   0 |  41-bit time   |  10-bit node |  12-bit sequence
 *  sign  ms since epoch   machine id    counter inside 1 ms
 * </pre>
 *
 * <p>The sign bit stays 0 so IDs are positive {@code long}s. Time is the highest field, so a bigger time always means a bigger ID, and IDs sort by creation time.
 */
public final class SnowflakeLayout {

    /** Bits for milliseconds since the epoch. 2^41 ms is about 69.7 years. */
    public static final int TIMESTAMP_BITS = 41;
    /** Bits for the node (machine) number. */
    public static final int NODE_BITS = 10;
    /** Bits for the counter inside one millisecond. */
    public static final int SEQUENCE_BITS = 12;

    /** Largest timestamp offset that fits: 2^41 - 1. */
    public static final long MAX_TIMESTAMP = (1L << TIMESTAMP_BITS) - 1;
    /** Largest node number: 1023. */
    public static final int MAX_NODE = (1 << NODE_BITS) - 1;
    /** Largest sequence number: 4095, so 4096 IDs per millisecond per node. */
    public static final int MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1;

    /** Shift that moves the node field into place. */
    static final int NODE_SHIFT = SEQUENCE_BITS;
    /** Shift that moves the timestamp field into place. */
    static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_BITS;

    /** Not instantiable: this class only holds constants and static helpers. */
    private SnowflakeLayout() {
    }

    /**
     * Packs the three fields into one ID.
     *
     * @throws IllegalArgumentException if any field is outside its bit range
     */
    public static long compose(long timestamp, int node, int sequence) {
        if (timestamp < 0 || timestamp > MAX_TIMESTAMP) {
            throw new IllegalArgumentException("timestamp out of range: " + timestamp);
        }
        if (node < 0 || node > MAX_NODE) {
            throw new IllegalArgumentException("node out of range: " + node);
        }
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("sequence out of range: " + sequence);
        }
        return (timestamp << TIMESTAMP_SHIFT) | ((long) node << NODE_SHIFT) | sequence;
    }

    /** Returns the timestamp field: milliseconds since the generator's epoch (not since 1970). */
    public static long timestamp(long id) {
        return id >>> TIMESTAMP_SHIFT;
    }

    /** Returns the node field. */
    public static int node(long id) {
        return (int) ((id >>> NODE_SHIFT) & MAX_NODE);
    }

    /** Returns the sequence field. */
    public static int sequence(long id) {
        return (int) (id & MAX_SEQUENCE);
    }

    /** Returns the real creation time of {@code id} as Unix milliseconds, given the epoch the generator used. */
    public static long unixMillis(long id, long epochMillis) {
        return epochMillis + timestamp(id);
    }
}
