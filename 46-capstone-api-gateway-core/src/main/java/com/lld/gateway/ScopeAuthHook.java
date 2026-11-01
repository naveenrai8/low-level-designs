package com.lld.gateway;

/** Checks that the identified caller holds the scope the route needs. Missing scope means 403 (we know who you are, and you may not). It must run after a hook that identifies the caller. */
public final class ScopeAuthHook implements AuthHook {

    /** Returns pass when the route needs no scope or the caller has it. Otherwise 403. With nobody identified it answers 401 with a challenge (RFC 9110 section 15.5.2 requires one), because a scope check without a caller is a chain-order bug and 401 is the safe answer. */
    @Override
    public AuthDecision check(GatewayRequest request, Route route, Principal current) {
        if (route.requiredScope() == null) {
            return AuthDecision.pass(current);
        }
        if (current == null) {
            return AuthDecision.reject(GatewayResponse.of(401, "unauthorized").withHeader("WWW-Authenticate", "ApiKey"));
        }
        if (!current.scopes().contains(route.requiredScope())) {
            return AuthDecision.reject(GatewayResponse.of(403, "forbidden"));
        }
        return AuthDecision.pass(current);
    }
}
