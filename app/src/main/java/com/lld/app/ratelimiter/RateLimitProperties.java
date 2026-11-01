package com.lld.app.ratelimiter;

import com.lld.ratelimiter.RateLimiterFactory.Algorithm;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code lld.rate-limit.*} settings (see application.yml).
 *
 * @param algorithm which algorithm to use (default token bucket)
 * @param permits how many requests are allowed per window (default 5)
 * @param window how long the limit is counted over (default 1 second)
 * @param evictionInterval how often idle keys are forgotten (default 30 seconds)
 */
@ConfigurationProperties("lld.rate-limit")
public record RateLimitProperties(
        @DefaultValue("TOKEN_BUCKET") Algorithm algorithm,
        @DefaultValue("5") long permits,
        @DefaultValue("1s") Duration window,
        @DefaultValue("30s") Duration evictionInterval) {
}
