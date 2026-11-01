package com.lld.app.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code lld.booking.*} settings (see application.yml). The sweep interval is read by the {@code @Scheduled} placeholder in BookingConfig, so it is not repeated here.
 *
 * @param holdTtl how long a hold lasts (default 5 minutes)
 * @param idempotencyTtl how long a confirm answer is remembered for replays (default 24 hours)
 * @param expiredHoldRetention how long an expired hold id answers 410 before it becomes 404 (default 1 hour)
 */
@ConfigurationProperties("lld.booking")
public record BookingProperties(
        @DefaultValue("5m") Duration holdTtl,
        @DefaultValue("24h") Duration idempotencyTtl,
        @DefaultValue("1h") Duration expiredHoldRetention) {
}
