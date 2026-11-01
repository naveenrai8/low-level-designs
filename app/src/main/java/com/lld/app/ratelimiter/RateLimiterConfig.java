package com.lld.app.ratelimiter;

import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import com.lld.ratelimiter.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Connects the framework-free core to Spring. The core never sees a Spring type. */
@Configuration(proxyBeanMethods = false)
class RateLimiterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfig.class);

    /** Provides the real clock as a bean, so tests can swap in frozen time (see RateLimitControllerTest). */
    @Bean
    Ticker rateLimiterTicker() {
        return Ticker.system();
    }

    /** Builds the limiter from the {@code lld.rate-limit.*} settings and logs which algorithm and limit are in use. */
    @Bean
    KeyedRateLimiter<?> rateLimiter(RateLimitProperties props, Ticker ticker) {
        RateLimit limit = new RateLimit(props.permits(), props.window());
        log.info("rate limiter: {} at {} per {}", props.algorithm(), limit.permits(), limit.window());
        return RateLimiterFactory.create(props.algorithm(), limit, ticker);
    }

    /** Keeps memory bounded by forgetting idle keys. Eviction loses nothing, so the interval only trades memory against the cost of scanning. */
    @Bean
    IdleKeyEvictor idleKeyEvictor(KeyedRateLimiter<?> limiter) {
        return new IdleKeyEvictor(limiter);
    }

    /** Runs {@link KeyedRateLimiter#evictIdle()} on a timer. */
    static final class IdleKeyEvictor {
        private final KeyedRateLimiter<?> limiter;

        /** Creates an evictor for {@code limiter}. */
        IdleKeyEvictor(KeyedRateLimiter<?> limiter) {
            this.limiter = limiter;
        }

        /** Forgets idle keys every {@code lld.rate-limit.eviction-interval} (30 s by default) and logs how many were dropped. */
        @Scheduled(fixedDelayString = "${lld.rate-limit.eviction-interval:30s}")
        void evict() {
            int evicted = limiter.evictIdle();
            if (evicted > 0) {
                log.debug("evicted {} idle keys, {} still tracked", evicted, limiter.trackedKeys());
            }
        }
    }
}
