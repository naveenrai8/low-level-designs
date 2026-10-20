package com.lld.app.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lld.ratelimiter.Ticker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full context, real limiter (5/s token bucket from application.yml), real HTTP mapping. Time is
 * frozen: with the system clock a token refills every 200ms, so a slow CI box would flake.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RateLimitControllerTest.FrozenTime.class)
class RateLimitControllerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FrozenTime {
        @Bean
        @Primary
        Ticker frozenTicker() {
            return () -> 0L;
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void grantsTheLimitThenReturns429WithRetryAfterInWholeSeconds() throws Exception {
        for (int i = 4; i >= 0; i--) {
            mvc.perform(post("/api/rate-limit/alice"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(i)));
        }

        mvc.perform(post("/api/rate-limit/alice"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    void keysAreIndependent() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/rate-limit/carol"));
        }
        mvc.perform(post("/api/rate-limit/dave")).andExpect(status().isOk());
    }

    @Test
    void aRequestThatCanNeverSucceedIsA400NotA429() throws Exception {
        mvc.perform(post("/api/rate-limit/erin").param("permits", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void retryAfterRoundsUp() {
        assertEquals(1, RateLimitController.retryAfterSeconds(Duration.ofMillis(1)));
        assertEquals(1, RateLimitController.retryAfterSeconds(Duration.ofSeconds(1)));
        assertEquals(2, RateLimitController.retryAfterSeconds(Duration.ofMillis(1_001)));
    }
}
