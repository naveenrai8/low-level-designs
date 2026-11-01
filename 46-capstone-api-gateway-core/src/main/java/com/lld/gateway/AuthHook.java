package com.lld.gateway;

/** One step of the auth chain. Hooks run in order, before the rate limiter and before any upstream is touched. */
@FunctionalInterface
public interface AuthHook {

    /**
     * Checks one thing about the request.
     *
     * @param request the request
     * @param route the matched route (it carries the required scope)
     * @param current who earlier hooks identified, or null if nobody yet
     * @return pass (optionally naming the caller) or reject with the response to send
     */
    AuthDecision check(GatewayRequest request, Route route, Principal current);
}
