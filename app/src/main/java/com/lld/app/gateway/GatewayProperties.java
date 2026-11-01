package com.lld.app.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code lld.gateway.*} settings (see application.yml).
 *
 * @param permits requests each client may make per route per window
 * @param window the rate-limit window
 * @param maxAttempts most upstream tries per request, first try included
 * @param deadline most total time per request, retries included
 * @param perTryTimeout most time for one upstream call
 * @param breakerMinCalls calls the breaker needs to see before it may open
 * @param breakerOpenWait how long the breaker stays open before it lets a probe through
 * @param maxBodyBytes largest request body accepted; bigger is answered 413 before anything else runs
 */
@ConfigurationProperties("lld.gateway")
public record GatewayProperties(
        @DefaultValue("5") long permits,
        @DefaultValue("1m") Duration window,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("5s") Duration deadline,
        @DefaultValue("2s") Duration perTryTimeout,
        @DefaultValue("4") int breakerMinCalls,
        @DefaultValue("30s") Duration breakerOpenWait,
        @DefaultValue("1048576") int maxBodyBytes) {
}
