package com.lld.gateway;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Cleans a request path once, at the front door, so that the path we ROUTE on is exactly the path we FORWARD.
 *
 * <p>Without this, {@code /orders/../internal/purge} would match the {@code /orders/{id}} route (the router sees a segment called {@code ..}), but a backend that resolves dot-segments would act on {@code /internal/purge}. Auth and rate limits would have been applied to the wrong route.
 */
final class PathNormalizer {

    /** Not instantiable: this class only holds a static helper. */
    private PathNormalizer() {
    }

    /**
     * Removes dot-segments (RFC 3986 section 5.2.4) and merges repeated slashes, and returns the clean path starting with '/'.
     *
     * <p>It rejects with {@link IllegalArgumentException} what cannot be cleaned safely: a {@code ..} that climbs above the root, and a percent-encoded dot or slash ({@code %2e}, {@code %2f}, any case), because a backend may decode it later and see a different path than we routed.
     */
    static String normalize(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("\\")) {
            throw new IllegalArgumentException("encoded dot, slash or backslash in path");
        }
        Deque<String> out = new ArrayDeque<>();
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.isEmpty()) {
                    throw new IllegalArgumentException("path climbs above the root");
                }
                out.removeLast();
            } else {
                out.addLast(segment);
            }
        }
        return "/" + String.join("/", out);
    }
}
