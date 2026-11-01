package com.lld.gateway;

import java.time.Duration;

/** A backend service the gateway forwards to. Production code would wrap an HTTP client; tests use in-memory fakes. */
public interface Upstream {

    /**
     * Handles one request and returns the backend's answer.
     *
     * <p>Error contract: return a response for anything the backend answered (a 404 or a 500 is still an answer). Throw {@link UpstreamTimeoutException} when {@code timeout} ran out and {@link UpstreamException} when the backend could not be reached. A 5xx response and these exceptions all count as failures for the circuit breaker.
     *
     * @param request the request, with path parameters filled in
     * @param timeout how long the gateway is still willing to wait, already shrunk to fit the request deadline; a real HTTP client sets its read timeout from it
     */
    GatewayResponse handle(GatewayRequest request, Duration timeout);
}
