package com.lld.app.notify;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code lld.notify.*} settings (see application.yml). The run interval is read by the {@code @Scheduled} placeholder in NotifyConfig.
 *
 * @param rateLimitPerMinute messages per user per minute
 * @param maxAttempts channel calls per message before the DLQ
 * @param backoffBase the first retry wait
 * @param backoffCap the longest retry wait
 * @param jitterSeed the seed of the jitter, so runs are repeatable
 */
@ConfigurationProperties("lld.notify")
public record NotifyProperties(
        @DefaultValue("5") int rateLimitPerMinute,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1s") Duration backoffBase,
        @DefaultValue("1m") Duration backoffCap,
        @DefaultValue("42") long jitterSeed) {
}
