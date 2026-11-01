package com.lld.logging;

import java.util.Map;

/**
 * Human-readable format: {@code 2026-01-01T00:00:00Z INFO [main] shop.pay - paid {req=7} amount=500 | java.lang.IllegalStateException: boom}.
 *
 * <p>Carriage returns and line feeds in the message, MDC, fields and error text are written as the two characters backslash-r and backslash-n, so user input cannot forge a second log line (CWE-117). The JSON formatter already escapes them.
 */
public final class PatternFormatter implements LogFormatter {

    /** Builds the single text line. The MDC part and the fields part are left out when empty. */
    @Override
    public String format(LogEvent e) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(e.time()).append(' ').append(e.level()).append(" [").append(e.thread()).append("] ").append(e.logger()).append(" - ").append(clean(e.message()));
        if (!e.context().isEmpty()) {
            sb.append(" {");
            boolean first = true;
            for (Map.Entry<String, String> kv : e.context().entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(kv.getKey()).append('=').append(clean(kv.getValue()));
            }
            sb.append('}');
        }
        for (Map.Entry<String, Object> kv : e.fields().entrySet()) {
            sb.append(' ').append(clean(kv.getKey())).append('=').append(clean(String.valueOf(kv.getValue())));
        }
        if (e.error() != null) {
            sb.append(" | ").append(clean(String.valueOf(e.error())));
        }
        return sb.toString();
    }

    /** Replaces CR and LF with their two-character escapes. Returns the text unchanged (no copy) when it has none. */
    private static String clean(String text) {
        if (text == null || (text.indexOf('\n') < 0 && text.indexOf('\r') < 0)) {
            return text;
        }
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
