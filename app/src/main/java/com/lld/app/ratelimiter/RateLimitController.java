package com.lld.app.ratelimiter;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.RateLimiter;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo endpoint: "spend {@code permits} for {@code key}". In a real service the limiter sits in a
 * filter in front of business endpoints; an explicit endpoint makes the behaviour easy to curl.
 */
@RestController
@RequestMapping("/api/rate-limit")
class RateLimitController {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter limiter;

    RateLimitController(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @PostMapping("/{key}")
    ResponseEntity<?> acquire(@PathVariable String key, @RequestParam(defaultValue = "1") int permits) {
        Decision d;
        try {
            d = limiter.tryAcquire(key, permits);
        } catch (IllegalArgumentException e) {
            // Translate HERE, around exactly the domain call. A global IAE→400 advice would also
            // turn Spring's/the JDK's own IAEs (server bugs) into 400s.
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
        DecisionResponse body = new DecisionResponse(d.allowed(), d.remaining(), d.retryAfter().toMillis());
        if (d.allowed()) {
            return ResponseEntity.ok().header(REMAINING_HEADER, Long.toString(d.remaining())).body(body);
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(REMAINING_HEADER, Long.toString(d.remaining()))
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(d.retryAfter())))
                .body(body);
    }

    /**
     * HTTP Retry-After is whole seconds (RFC 9110 §10.2.3). Round UP: rounding down tells the
     * client to come back before the permit exists, which guarantees a second 429.
     */
    static long retryAfterSeconds(Duration wait) {
        long seconds = wait.toSeconds();
        return wait.equals(Duration.ofSeconds(seconds)) ? seconds : seconds + 1;
    }

    record DecisionResponse(boolean allowed, long remaining, long retryAfterMillis) {
    }
}
