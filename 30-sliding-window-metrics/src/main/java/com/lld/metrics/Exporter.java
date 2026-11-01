package com.lld.metrics;

/**
 * The seam between collecting metrics and shipping them somewhere (a log, Prometheus, a vendor API).
 *
 * <p>The exporter gets an immutable {@link RegistrySnapshot}, so it never touches live time slices or locks and a slow exporter cannot slow down writers.
 */
@FunctionalInterface
public interface Exporter {

    /** Handles one snapshot. An exception thrown here is the caller's to catch; the metrics keep working. */
    void export(RegistrySnapshot snapshot);
}
