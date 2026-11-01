package com.lld.gateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the route for a request: the most specific path wins FIRST, and only then is the method checked.
 *
 * <p>The order matters for security. If the method were checked first, {@code DELETE /admin/users/7} would skip the {@code /admin} route (GET only, needs the admin scope) and fall through to a catch-all {@code /} route that allows DELETE and needs no scope. So the most specific path decides, and a wrong method on it is a 405, never a fall-through.
 *
 * <p>Cost: one scan over all routes per lookup, so O(routes x depth). Fine for hundreds of routes. A segment trie would make it O(depth) (a design delta, not coded).
 */
public final class RouteTable {

    /** What a lookup found. */
    public enum Result {
        /** A route matches the path and the method. */
        FOUND,
        /** No route matches the path: answer 404. */
        NOT_FOUND,
        /** The most specific route for the path does not allow the method: answer 405 and list the allowed methods. */
        METHOD_NOT_ALLOWED
    }

    /**
     * The answer of a lookup.
     *
     * @param result what was found
     * @param route the chosen route, or null unless FOUND
     * @param params the captured path parameters, empty unless FOUND
     * @param allowed the methods the most specific path accepts, for the {@code Allow} header of a 405
     */
    public record Lookup(Result result, Route route, Map<String, String> params, Set<String> allowed) {
    }

    /** Deeper wins. At equal depth the first segment where one route has fixed text and the other a placeholder decides, and fixed text wins. */
    private static final Comparator<Route> SPECIFICITY = Comparator.comparingInt(Route::depth).thenComparing(RouteTable::compareLiterals);

    private final List<Route> routes;

    /** Builds the table. Rejects two routes with the same shape and a shared method, because which one wins would depend on list order. {@code /users/{id}} and {@code /users/{name}} have the same shape. */
    public RouteTable(List<Route> routes) {
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                Route a = routes.get(i);
                Route b = routes.get(j);
                if (a.shape().equals(b.shape()) && a.methods().stream().anyMatch(b.methods()::contains)) {
                    throw new IllegalArgumentException("routes " + a.name() + " and " + b.name() + " overlap");
                }
            }
        }
        this.routes = List.copyOf(routes);
    }

    /** Compares two routes of any depth by their leading segments: at the first position where one is fixed text and the other a placeholder, the fixed one is more specific. */
    private static int compareLiterals(Route a, Route b) {
        int n = Math.min(a.depth(), b.depth());
        for (int i = 0; i < n; i++) {
            if (a.literalAt(i) != b.literalAt(i)) {
                return a.literalAt(i) ? 1 : -1;
            }
        }
        return 0;
    }

    /** Returns the routes, for startup checks such as "every route has an upstream". */
    public List<Route> routes() {
        return routes;
    }

    /**
     * Looks up a request.
     *
     * <p>Step 1: keep every route whose pattern matches the start of the path. None left means 404. Step 2: keep only the most specific ones (same shape, so they differ only by method). Step 3: if one of them allows the method, that is the route. Otherwise 405 listing the methods of those routes, and nothing else.
     */
    public Lookup lookup(String method, String path) {
        String m = method.toUpperCase(Locale.ROOT);
        List<Route> pathMatches = new ArrayList<>();
        for (Route r : routes) {
            if (r.match(path) != null) {
                pathMatches.add(r);
            }
        }
        if (pathMatches.isEmpty()) {
            return new Lookup(Result.NOT_FOUND, null, Map.of(), Set.of());
        }
        Route best = pathMatches.stream().max(SPECIFICITY).orElseThrow();
        List<Route> top = pathMatches.stream().filter(r -> r.shape().equals(best.shape())).toList();
        for (Route r : top) {
            if (r.allows(m)) {
                return new Lookup(Result.FOUND, r, r.match(path), Set.of());
            }
        }
        Set<String> allowed = new TreeSet<>();
        for (Route r : top) {
            allowed.addAll(r.methods());
            if (r.methods().contains("GET")) {
                allowed.add("HEAD");
            }
        }
        return new Lookup(Result.METHOD_NOT_ALLOWED, null, Map.of(), allowed);
    }
}
