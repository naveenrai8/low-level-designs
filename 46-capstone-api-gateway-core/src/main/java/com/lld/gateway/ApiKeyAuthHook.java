package com.lld.gateway;

import java.util.Map;

/** Identifies the caller from the {@code X-API-Key} header. Missing or unknown key means 401 (we do not know who you are). */
public final class ApiKeyAuthHook implements AuthHook {

    private final Map<String, Principal> keys;

    /** Creates the hook from a map of secret key to the client it belongs to. */
    public ApiKeyAuthHook(Map<String, Principal> keys) {
        this.keys = Map.copyOf(keys);
    }

    /** Returns pass with the client, or a 401 with a {@code WWW-Authenticate} header. The response never says whether the key was missing or wrong, so it leaks nothing. */
    @Override
    public AuthDecision check(GatewayRequest request, Route route, Principal current) {
        String key = request.header("x-api-key");
        Principal p = key == null ? null : keys.get(key);
        if (p == null) {
            return AuthDecision.reject(GatewayResponse.of(401, "unauthorized").withHeader("WWW-Authenticate", "ApiKey"));
        }
        return AuthDecision.pass(p);
    }
}
