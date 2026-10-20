package com.lld.app.ratelimiter;

import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiters;
import com.lld.ratelimiter.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Wires the framework-free core into Spring. The core never sees a Spring type. */
@Configuration(proxyBeanMethods = false)
class RateLimiterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfig.class);

    /** A bean so tests can swap in frozen time (see RateLimitControllerTest). */
    @Bean
    Ticker rateLimiterTicker() {
        return Ticker.system();
    }

    @Bean
    KeyedRateLimiter<?> rateLimiter(RateLimitProperties props, Ticker ticker) {
        RateLimit limit = new RateLimit(props.permits(), props.window());
        log.info("rate limiter: {} at {} per {}", props.algorithm(), limit.permits(), limit.window());
        return RateLimiters.create(props.algorithm(), limit, ticker);
    }

    /** Bounds memory; lossless, so the interval only trades memory for scan cost. */
    @Bean
    IdleKeyEvictor idleKeyEvictor(KeyedRateLimiter<?> limiter) {
        return new IdleKeyEvictor(limiter);
    }

    static final class IdleKeyEvictor {
        private final KeyedRateLimiter<?> limiter;

        IdleKeyEvictor(KeyedRateLimiter<?> limiter) {
            this.limiter = limiter;
        }

        @Scheduled(fixedDelayString = "${lld.rate-limit.eviction-interval:30s}")
        void evict() {
            int evicted = limiter.evictIdle();
            if (evicted > 0) {
                log.debug("evicted {} idle keys, {} still tracked", evicted, limiter.trackedKeys());
            }
        }
    }
}
