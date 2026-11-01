package com.lld.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One row of the route table: "these methods on this path prefix go to that upstream".
 *
 * <p>The pattern is matched <em>by whole segments</em>: {@code /orders} matches {@code /orders} and {@code /orders/42/items} but not {@code /ordersX}. A segment like {@code {id}} matches any one segment and captures it.
 *
 * @param name a short unique name, used in the rate-limit key and in logs
 * @param methods the allowed HTTP methods, upper-cased
 * @param pattern the path pattern, for example {@code /orders/{id}}
 * @param upstream the name of the upstream to call
 * @param requiredScope the scope the caller needs, or null for "any authenticated caller"
 */
public record Route(String name, Set<String> methods, String pattern, String upstream, String requiredScope) {

    /** Checks the fields and upper-cases the methods. */
    public Route {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(upstream, "upstream");
        if (name.contains("|")) {
            throw new IllegalArgumentException("a route name may not contain '|' (it is part of the rate-limit key): " + name);
        }
        if (methods.isEmpty() || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("a route needs a method and a pattern starting with '/': " + name);
        }
        methods = methods.stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Splits a path into its non-empty segments, so {@code /a//b/} and {@code /a/b} are the same path. */
    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** How many segments the pattern has. A bigger number means a longer, more specific prefix. */
    int depth() {
        return segments(pattern).size();
    }

    /** Tells whether pattern segment {@code i} is fixed text. The route table compares routes of equal depth by their first differing segment: fixed text beats a placeholder, so {@code /users/me} beats {@code /users/{id}}. */
    boolean literalAt(int i) {
        return !isParam(segments(pattern).get(i));
    }

    /** The pattern with every placeholder name erased, for example {@code /users/{}}. Two routes with the same shape would match exactly the same paths. */
    String shape() {
        return "/" + String.join("/", segments(pattern).stream().map(s -> isParam(s) ? "{}" : s).toList());
    }

    /** Tells whether this route accepts the method. HEAD is accepted wherever GET is (RFC 9110 section 9.3.2: HEAD is GET without a body). */
    boolean allows(String method) {
        return methods.contains(method) || (method.equals("HEAD") && methods.contains("GET"));
    }

    /** Tells whether a pattern segment is a {@code {param}} placeholder. */
    private static boolean isParam(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    /** Matches the pattern against the start of {@code path}, whole segments only. Returns the captured parameters, or null when it does not match. */
    Map<String, String> match(String path) {
        List<String> p = segments(pattern);
        List<String> s = segments(path);
        if (p.size() > s.size()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < p.size(); i++) {
            if (isParam(p.get(i))) {
                params.put(p.get(i).substring(1, p.get(i).length() - 1), s.get(i));
            } else if (!p.get(i).equals(s.get(i))) {
                return null;
            }
        }
        return params;
    }
}
