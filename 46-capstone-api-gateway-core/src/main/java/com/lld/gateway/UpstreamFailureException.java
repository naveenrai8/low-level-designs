package com.lld.gateway;

/** Internal: wraps a 5xx answer so the breaker and the retry loop can see it as a failure. The gateway unwraps it again and returns the backend's own response. */
final class UpstreamFailureException extends UpstreamException {

    private static final long serialVersionUID = 1L;

    private final transient GatewayResponse response;

    /** Wraps the failing response. */
    UpstreamFailureException(GatewayResponse response) {
        super("upstream answered " + response.status());
        this.response = response;
    }

    /** Returns the backend's own response, so the caller sees what the backend said. */
    GatewayResponse response() {
        return response;
    }
}
