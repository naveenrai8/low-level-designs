package com.lld.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable picture of every series in a {@link KeyedMetrics} registry at one moment, sorted by key.
 *
 * @param takenAtNanos the ticker reading when the picture was taken
 * @param metrics      one snapshot per key, read-only
 */
public record RegistrySnapshot(long takenAtNanos, Map<String, MetricSnapshot> metrics) {

    /** Copies the map into a sorted, read-only one so nobody can change the snapshot after it is made. */
    public RegistrySnapshot {
        metrics = Collections.unmodifiableMap(new TreeMap<>(metrics));
    }
}
