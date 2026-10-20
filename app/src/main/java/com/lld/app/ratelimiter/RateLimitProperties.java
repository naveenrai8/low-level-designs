package com.lld.app.ratelimiter;

import com.lld.ratelimiter.RateLimiters.Algorithm;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code lld.rate-limit.*} — see application.yml. */
@ConfigurationProperties("lld.rate-limit")
public record RateLimitProperties(
        @DefaultValue("TOKEN_BUCKET") Algorithm algorithm,
        @DefaultValue("5") long permits,
        @DefaultValue("1s") Duration window,
        @DefaultValue("30s") Duration evictionInterval) {
}
