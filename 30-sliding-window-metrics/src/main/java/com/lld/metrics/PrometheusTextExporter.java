package com.lld.metrics;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Turns a snapshot into the Prometheus text exposition format, as a "summary" with quantile lines plus {@code _sum} and {@code _count}.
 *
 * <p>The quantile lines come from the sliding window. {@code _sum} and {@code _count} are the lifetime totals, which only go up, because Prometheus reads a drop as a counter reset and {@code rate()} would spike. The latest text is kept in {@link #lastText()}; serving it over HTTP is outside this topic.
 */
public final class PrometheusTextExporter implements Exporter {

    private static final double[] QUANTILES = {0.5, 0.9, 0.99, 0.999};
    private static final String[] QUANTILE_LABELS = {"0.5", "0.9", "0.99", "0.999"};

    private final String metricName;
    private final AtomicReference<String> last = new AtomicReference<>("");

    /** Creates an exporter that names every series {@code metricName} and puts the registry key in a {@code key} label. Throws {@link IllegalArgumentException} if the name is not a legal Prometheus metric name. */
    public PrometheusTextExporter(String metricName) {
        if (!metricName.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) {
            throw new IllegalArgumentException("not a valid Prometheus metric name: " + metricName);
        }
        this.metricName = metricName;
    }

    /** Formats the snapshot and keeps the text. */
    @Override
    public void export(RegistrySnapshot snapshot) {
        last.set(format(snapshot));
    }

    /** Returns the text of the most recent export (empty before the first one). */
    public String lastText() {
        return last.get();
    }

    /** Builds the exposition text. An empty window prints no quantile lines, because there is no honest number to show, but the cumulative totals are always printed. */
    String format(RegistrySnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        out.append("# TYPE ").append(metricName).append(" summary\n");
        for (Map.Entry<String, MetricSnapshot> e : snapshot.metrics().entrySet()) {
            String key = escape(e.getKey());
            MetricSnapshot m = e.getValue();
            for (int i = 0; i < QUANTILES.length; i++) {
                OptionalLong v = m.percentile(QUANTILES[i]);
                if (v.isPresent()) {
                    out.append(metricName).append("{key=\"").append(key).append("\",quantile=\"").append(QUANTILE_LABELS[i]).append("\"} ").append(v.getAsLong()).append('\n');
                }
            }
            out.append(metricName).append("_sum{key=\"").append(key).append("\"} ").append(m.lifetimeSum()).append('\n');
            out.append(metricName).append("_count{key=\"").append(key).append("\"} ").append(m.lifetimeCount()).append('\n');
        }
        return out.toString();
    }

    /** Escapes backslash, double quote and newline, the three characters the format says must be escaped in a label value. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
