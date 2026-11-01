package com.lld.gateway;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One request as the gateway sees it, with no HTTP library types so the core stays framework-free.
 *
 * @param method the HTTP method, upper-cased (GET, POST, ...)
 * @param path the path without the query string, for example {@code /orders/42}
 * @param headers header values with lower-case names (HTTP header names are case-insensitive)
 * @param body the request body, or an empty string
 * @param pathParams values captured from the route pattern, for example {@code id=42}; empty until a route matched
 * @param query the raw query string without the '?', or an empty string; it is forwarded untouched
 */
public record GatewayRequest(String method, String path, Map<String, String> headers, String body, Map<String, String> pathParams, String query) {

    /** Normalises the method to upper case and the header names to lower case, and makes the maps unmodifiable. */
    public GatewayRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        method = method.toUpperCase(Locale.ROOT);
        Map<String, String> lower = new HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        headers = Map.copyOf(lower);
        body = body == null ? "" : body;
        pathParams = Map.copyOf(pathParams);
        query = query == null ? "" : query;
    }

    /** Creates a request with no query string. */
    public GatewayRequest(String method, String path, Map<String, String> headers, String body, Map<String, String> pathParams) {
        this(method, path, headers, body, pathParams, "");
    }

    /** Creates a request with no headers and no body. */
    public static GatewayRequest of(String method, String path) {
        return new GatewayRequest(method, path, Map.of(), "", Map.of());
    }

    /** Returns a copy with one more header (replacing any header of the same name). */
    public GatewayRequest withHeader(String name, String value) {
        Map<String, String> copy = new HashMap<>(headers);
        copy.put(name.toLowerCase(Locale.ROOT), value);
        return new GatewayRequest(method, path, copy, body, pathParams, query);
    }

    /** Returns a copy with the given body. */
    public GatewayRequest withBody(String newBody) {
        return new GatewayRequest(method, path, headers, newBody, pathParams, query);
    }

    /** Returns a copy carrying the path parameters captured by the matched route. */
    public GatewayRequest withPathParams(Map<String, String> params) {
        return new GatewayRequest(method, path, headers, body, params, query);
    }

    /** Returns a copy with another path. The gateway uses it to swap in the cleaned path. */
    public GatewayRequest withPath(String newPath) {
        return new GatewayRequest(method, newPath, headers, body, pathParams, query);
    }

    /** Returns a copy without the named header (case-insensitive). Used to strip credentials before forwarding. */
    public GatewayRequest without(String name) {
        Map<String, String> copy = new HashMap<>(headers);
        copy.remove(name.toLowerCase(Locale.ROOT));
        return new GatewayRequest(method, path, copy, body, pathParams, query);
    }

    /** Returns the header value, or null when absent. The name is matched case-insensitively. */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
