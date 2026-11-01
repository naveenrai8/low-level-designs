package com.lld.app.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lld.notify.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Full Spring context with one movable time source for the clock and the rate limiter: every status code of the notification API without sleeping. */
@SpringBootTest(properties = "lld.notify.run-interval=1h") // the timer must not race the tests; they call runDue themselves
@AutoConfigureMockMvc
@Import(NotificationControllerTest.ControlledTime.class)
class NotificationControllerTest {

    /** A clock that is also the limiter's ticker and moves only when a test says so. Starts at 17:00 UTC, which is noon in New York, so alice (quiet hours 22:00 to 07:00) is not in quiet hours. */
    static final class Movable extends Clock {
        final AtomicLong nanos = new AtomicLong();
        final Instant origin = Instant.parse("2026-01-05T17:00:00Z");

        /** Returns the fake time. */
        @Override
        public Instant instant() {
            return origin.plusNanos(nanos.get());
        }

        /** Returns UTC. */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** Zones do not matter here. */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Replaces the real clock (the limiter's ticker is derived from it) with the movable one. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledTime {
        /** The shared movable time source. */
        @Bean
        Movable movable() {
            return new Movable();
        }

        /** Primary clock for every consumer in this test context. */
        @Bean
        @Primary
        Clock primaryClock(Movable m) {
            return m;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    NotificationService service;
    @Autowired
    Movable time;
    @Autowired
    com.lld.notify.InMemoryPreferenceStore users;

    /** Posts a submit with an optional key. */
    private ResultActions submit(String key, String json) throws Exception {
        var req = post("/notifications").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    /** A valid otp request body for alice with the given code. */
    private static String otp(String code) {
        return "{\"userId\":\"alice\",\"channel\":\"SMS\",\"template\":\"otp\",\"variables\":{\"code\":\"" + code + "\"}}";
    }

    /** Proves a submit is 202 with an id and a Location, and GET shows PENDING and then SENT after the worker runs. */
    @Test
    void submitIs202AndGetShowsStatus() throws Exception {
        var result = submit("t-1", otp("1")).andExpect(status().isAccepted()).andExpect(jsonPath("$.replay").value(false)).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertEquals("/notifications/" + id, result.getResponse().getHeader("Location"));
        mvc.perform(get("/notifications/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        service.runDue();
        mvc.perform(get("/notifications/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT")).andExpect(jsonPath("$.attempts").value(1));
    }

    /** Proves the same key and body is 200 with the same id, and the same key with another body is 422. */
    @Test
    void replayIs200AndDifferentBodyIs422() throws Exception {
        String first = submit("t-2", otp("2")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String second = submit("t-2", otp("2")).andExpect(status().isOk()).andExpect(jsonPath("$.replay").value(true)).andReturn().getResponse().getContentAsString();
        assertEquals((Object) com.jayway.jsonpath.JsonPath.read(first, "$.id"), com.jayway.jsonpath.JsonPath.read(second, "$.id"));
        submit("t-2", otp("3")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.status").value(422));
    }

    /** Proves validation errors are 400: missing key, unknown user, unknown template, missing template variable, a null variable value, missing field, bad enum. */
    @Test
    void validationErrorsAre400() throws Exception {
        submit(null, otp("1")).andExpect(status().isBadRequest());
        submit("t-3a", "{\"userId\":\"nobody\",\"channel\":\"SMS\",\"template\":\"otp\",\"variables\":{\"code\":\"1\"}}").andExpect(status().isBadRequest());
        submit("t-3b", "{\"userId\":\"alice\",\"channel\":\"SMS\",\"template\":\"nope\"}").andExpect(status().isBadRequest());
        submit("t-3c", "{\"userId\":\"alice\",\"channel\":\"SMS\",\"template\":\"otp\",\"variables\":{}}").andExpect(status().isBadRequest());
        submit("t-3d", "{\"channel\":\"SMS\",\"template\":\"otp\"}").andExpect(status().isBadRequest());
        submit("t-3f", "{\"userId\":\"alice\",\"channel\":\"SMS\",\"template\":\"otp\",\"variables\":{\"code\":null}}").andExpect(status().isBadRequest());
        submit("t-3e", "{\"userId\":\"alice\",\"channel\":\"FAX\",\"template\":\"otp\"}").andExpect(status().isBadRequest());
    }

    /** Proves a failed validation does not use up the key: the same key works once the body is fixed. */
    @Test
    void invalidRequestDoesNotClaimTheKey() throws Exception {
        submit("t-4", "{\"userId\":\"alice\",\"channel\":\"SMS\",\"template\":\"otp\",\"variables\":{}}").andExpect(status().isBadRequest());
        submit("t-4", otp("4")).andExpect(status().isAccepted());
    }

    /** Proves an unknown id is 404. */
    @Test
    void unknownIdIs404() throws Exception {
        mvc.perform(get("/notifications/ntf-none")).andExpect(status().isNotFound());
    }

    /** Proves the rate limit defers instead of dropping: after the per-minute limit, extra messages stay PENDING with a reason, and go out after a virtual minute. */
    @Test
    void rateLimitedMessagesAreDeferredThenSent() throws Exception {
        // A user of its own: the limiter bucket is per user and shared by every test in this Spring context, so alice's tokens may already be spent by other tests.
        users.put("limited", new com.lld.notify.UserPreferences(ZoneId.of("UTC"), java.util.Map.of(com.lld.notify.ChannelType.SMS, "+1"), java.util.Set.of(com.lld.notify.ChannelType.SMS), java.util.Optional.empty()));
        String last = null;
        for (int i = 0; i < 8; i++) {
            String body = submit("t-5-" + i, otp("r" + i).replace("alice", "limited")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            last = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        }
        service.runDue();
        mvc.perform(get("/notifications/" + last)).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.reason").value("deferred: rate limited"));
        time.nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        service.runDue();
        mvc.perform(get("/notifications/" + last)).andExpect(jsonPath("$.status").value("SENT"));
    }
}
