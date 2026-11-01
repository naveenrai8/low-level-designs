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

/** Demo endpoint: "spend {@code permits} for {@code key}". In a real service the limiter sits in a filter in front of the business endpoints; a separate endpoint just makes the behaviour easy to try with curl. */
@RestController
@RequestMapping("/api/rate-limit")
class RateLimitController {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter limiter;

    /** Creates the controller around the limiter bean. */
    RateLimitController(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * Tries to spend {@code permits} for {@code key} and turns the answer into HTTP.
     *
     * <p>Allowed gives 200 with an {@code X-RateLimit-Remaining} header. Denied gives 429 with {@code X-RateLimit-Remaining} and {@code Retry-After} (whole seconds). A request that could never succeed gives 400 with a problem detail.
     */
    @PostMapping("/{key}")
    ResponseEntity<?> acquire(@PathVariable String key, @RequestParam(defaultValue = "1") int permits) {
        Decision d;
        try {
            d = limiter.tryAcquire(key, permits);
        } catch (IllegalArgumentException e) {
            // Translate HERE, around exactly the domain call. A global IAE→400 handler would also turn Spring's and the JDK's own IAEs (server bugs) into 400s.
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
     * Turns a wait into whole seconds for the HTTP {@code Retry-After} header (RFC 9110 §10.2.3), rounding up.
     *
     * <p>Rounding down would tell the client to come back before the permit exists, which guarantees a second 429. Example: a 1 ms wait becomes 1 second, and 1,001 ms becomes 2 seconds.
     */
    static long retryAfterSeconds(Duration wait) {
        long seconds = wait.toSeconds();
        return wait.equals(Duration.ofSeconds(seconds)) ? seconds : seconds + 1;
    }

    /** The JSON body returned for both 200 and 429: was it allowed, how many permits are left, and how many milliseconds to wait (0 when allowed). */
    record DecisionResponse(boolean allowed, long remaining, long retryAfterMillis) {
    }
}
