package com.lld.gateway;

import java.util.HashMap;
import java.util.Map;

/**
 * One response from the gateway or from an upstream.
 *
 * @param status the HTTP status code
 * @param headers response headers, for example {@code Retry-After}
 * @param body the response body, or an empty string
 */
public record GatewayResponse(int status, Map<String, String> headers, String body) {

    /** Makes the header map unmodifiable and turns a null body into an empty one. */
    public GatewayResponse {
        headers = Map.copyOf(headers);
        body = body == null ? "" : body;
    }

    /** Creates a response with no headers. */
    public static GatewayResponse of(int status, String body) {
        return new GatewayResponse(status, Map.of(), body);
    }

    /** Returns a copy with one more header. */
    public GatewayResponse withHeader(String name, String value) {
        Map<String, String> copy = new HashMap<>(headers);
        copy.put(name, value);
        return new GatewayResponse(status, copy, body);
    }

    /** True for 5xx: the server side failed. These are the answers that count against an upstream's circuit breaker. 4xx is the caller's fault and never counts. */
    public boolean isServerError() {
        return status >= 500;
    }
}
