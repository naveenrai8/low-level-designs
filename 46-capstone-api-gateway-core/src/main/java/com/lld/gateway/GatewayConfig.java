package com.lld.gateway;

import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import com.lld.resilience.CircuitBreakerConfig;
import java.time.Duration;
import java.util.Objects;

/**
 * Every tunable of the gateway in one place.
 *
 * @param algorithm the rate-limit algorithm (Topic 21)
 * @param limit how many requests each client may make per route per window
 * @param breaker the circuit-breaker settings, used for every upstream (Topic 27)
 * @param maxAttempts the most tries per request, first try included; 1 turns retry off
 * @param backoffBase the first retry wait before jitter
 * @param backoffCap the largest retry wait
 * @param retryRatioPermille retry tokens earned per request in thousandths: 100 means "retries may add at most about 10% extra load"
 * @param retryMaxTokens the most retry tokens that can be saved up
 * @param deadline the most time one request may take in total, retries included
 * @param perTryTimeout the most time one single upstream call may take
 * @param maxBodyBytes the largest request body accepted; a bigger one is answered 413 before anything else runs
 */
public record GatewayConfig(RateLimiterFactory.Algorithm algorithm, RateLimit limit, CircuitBreakerConfig breaker, int maxAttempts, Duration backoffBase, Duration backoffCap, int retryRatioPermille, int retryMaxTokens, Duration deadline, Duration perTryTimeout, int maxBodyBytes) {

    /** Checks that nothing is missing and the numbers make sense. */
    public GatewayConfig {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(breaker, "breaker");
        if (maxAttempts < 1 || deadline.isZero() || deadline.isNegative() || perTryTimeout.isZero() || perTryTimeout.isNegative() || maxBodyBytes < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 and the deadline and per-try timeout must be positive, and maxBodyBytes must not be negative");
        }
    }
}
