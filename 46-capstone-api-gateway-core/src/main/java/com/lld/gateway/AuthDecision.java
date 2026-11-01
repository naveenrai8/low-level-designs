package com.lld.gateway;

/**
 * The verdict of one auth hook.
 *
 * @param principal who the caller is, or null if this hook did not identify anyone
 * @param rejection the response to send back, or null to let the request continue
 */
public record AuthDecision(Principal principal, GatewayResponse rejection) {

    /** Lets the request continue and passes on who the caller is (may be null for hooks that only check). */
    public static AuthDecision pass(Principal principal) {
        return new AuthDecision(principal, null);
    }

    /** Stops the request with this response. */
    public static AuthDecision reject(GatewayResponse response) {
        return new AuthDecision(null, response);
    }

    /** True when the request must stop here. */
    public boolean rejected() {
        return rejection != null;
    }
}
