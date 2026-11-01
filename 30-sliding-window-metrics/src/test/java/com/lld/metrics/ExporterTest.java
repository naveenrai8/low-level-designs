package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** F3: exporters get an immutable snapshot and cannot disturb the live metrics. */
class ExporterTest {

    private final FakeTicker ticker = new FakeTicker();
    private final KeyedMetrics reg = new KeyedMetrics(ticker, Duration.ofSeconds(10), 5, 5);

    /** The in-memory exporter receives a snapshot that does not change when more samples arrive. */
    @Test
    void exporterReceivesImmutableSnapshot() {
        InMemoryExporter exporter = new InMemoryExporter();
        reg.record("/a", 10);
        reg.exportTo(exporter);
        reg.record("/a", 20);
        RegistrySnapshot got = exporter.received().get(0);
        assertEquals(1, got.metrics().get("/a").count());
        assertThrows(UnsupportedOperationException.class, () -> got.metrics().clear());
        reg.exportTo(exporter);
        assertEquals(2, exporter.received().size());
        assertEquals(2, exporter.received().get(1).metrics().get("/a").count());
    }

    /** Prometheus _count and _sum are cumulative: they stay put after the samples leave the window, while the windowed quantiles disappear. */
    @Test
    void cumulativeTotalsNeverDecreaseAcrossExpiry() {
        PrometheusTextExporter exporter = new PrometheusTextExporter("lat");
        reg.record("/a", 100);
        reg.record("/a", 200);
        ticker.advanceSeconds(60);
        reg.exportTo(exporter);
        String text = exporter.lastText();
        assertTrue(text.contains("lat_count{key=\"/a\"} 2\n"), text);
        assertTrue(text.contains("lat_sum{key=\"/a\"} 300\n"), text);
        assertTrue(!text.contains("quantile"), "the window is empty, so no quantile lines");
        assertEquals(0, reg.snapshotAll().metrics().get("/a").count());
    }

    /** A metric name that Prometheus would reject is refused up front. */
    @Test
    void invalidMetricNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PrometheusTextExporter("9lives"));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusTextExporter("has-dash"));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusTextExporter(""));
        new PrometheusTextExporter("ok_name:sub1");
    }

    /** The Prometheus text has a TYPE line, quantile lines, _sum, _count and escaped labels; empty series print no quantiles. */
    @Test
    void prometheusTextFormat() {
        PrometheusTextExporter exporter = new PrometheusTextExporter("http_latency_us");
        reg.record("/a\"b", 100);
        reg.record("/a\"b", 200);
        reg.exportTo(exporter);
        String text = exporter.lastText();
        assertTrue(text.startsWith("# TYPE http_latency_us summary\n"));
        assertTrue(text.contains("http_latency_us{key=\"/a\\\"b\",quantile=\"0.5\"} 100\n"), text);
        assertTrue(text.contains("http_latency_us_sum{key=\"/a\\\"b\"} 300\n"), text);
        assertTrue(text.contains("http_latency_us_count{key=\"/a\\\"b\"} 2\n"), text);
        assertTrue(text.contains("http_latency_us_count{key=\"__other__\"} 0\n"), text);
        assertTrue(!text.contains("__other__\",quantile"), "no quantiles for an empty series");
    }
}
