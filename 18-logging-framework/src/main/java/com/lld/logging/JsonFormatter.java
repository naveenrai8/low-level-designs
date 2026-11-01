package com.lld.logging;

import java.util.Map;

/**
 * Structured format: one JSON object per line, so a log system can search by field instead of parsing prose.
 *
 * <p>Booleans and finite JDK numbers (Byte, Short, Integer, Long, BigInteger, BigDecimal, finite Float and Double) stay raw. Everything else is a string: NaN and Infinity (not valid JSON), and any custom Number, whose toString could inject keys. A null message is written as JSON null. Keys come out in a fixed order, so the same event always gives the same text.
 */
public final class JsonFormatter implements LogFormatter {

    /** Builds one line such as {@code {"ts":"...","level":"INFO","logger":"a","thread":"main","msg":"hi","mdc":{"req":"7"},"fields":{"amount":500}}}. */
    @Override
    public String format(LogEvent e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"ts\":");
        quote(sb, e.time().toString());
        sb.append(",\"level\":");
        quote(sb, e.level().name());
        sb.append(",\"logger\":");
        quote(sb, e.logger());
        sb.append(",\"thread\":");
        quote(sb, e.thread());
        sb.append(",\"msg\":");
        quote(sb, e.message());
        if (!e.context().isEmpty()) {
            sb.append(",\"mdc\":");
            object(sb, e.context());
        }
        if (!e.fields().isEmpty()) {
            sb.append(",\"fields\":");
            object(sb, e.fields());
        }
        if (e.error() != null) {
            sb.append(",\"error\":");
            quote(sb, e.error().toString());
        }
        return sb.append('}').toString();
    }

    /** Writes a map as a JSON object. */
    private static void object(StringBuilder sb, Map<String, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, ?> kv : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, kv.getKey());
            sb.append(':');
            Object v = kv.getValue();
            if (v == null) {
                sb.append("null");
            } else if (isSafeRaw(v)) {
                sb.append(v);
            } else {
                quote(sb, v.toString());
            }
        }
        sb.append('}');
    }

    /** True for values whose toString is always valid JSON: booleans and finite numbers of JDK types. */
    private static boolean isSafeRaw(Object v) {
        if (v instanceof Boolean || v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger || v instanceof java.math.BigDecimal) {
            return true;
        }
        if (v instanceof Double d) {
            return Double.isFinite(d);
        }
        return v instanceof Float f && Float.isFinite(f);
    }

    /** Writes a JSON string with quotes, escaping the characters JSON does not allow raw (quote, backslash, control characters). */
    private static void quote(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
