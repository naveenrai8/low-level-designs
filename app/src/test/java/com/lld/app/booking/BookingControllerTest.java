package com.lld.app.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lld.booking.BookingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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

/** Full Spring context with a controllable clock: every status code of the booking API, including expiry, without sleeping. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(BookingControllerTest.ControlledTime.class)
class BookingControllerTest {

    /** A clock that moves only when a test says so. */
    static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));

        /** Returns the fake time. */
        @Override
        public Instant instant() {
            return now.get();
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

        /** Moves time forward. */
        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    /** Replaces the real clock bean with the movable one. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledTime {
        /** Provides the movable clock as the primary Clock. */
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MovableClock clock;
    @Autowired
    BookingService service;

    /** Creates a fresh show so tests do not disturb each other (the Spring context and its clock are shared, so tests only use time relative to now). */
    private String newShow(String name) {
        service.addShow(name, Set.of("A1", "A2", "A3"));
        return name;
    }

    /** Posts a hold request and returns the result. */
    private ResultActions hold(String show, String seatsJson) throws Exception {
        return mvc.perform(post("/api/booking/shows/" + show + "/holds").contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":" + seatsJson + "}"));
    }

    /** Posts a confirm request with an optional key. */
    private ResultActions confirm(String holdId, String key, String payment) throws Exception {
        var req = post("/api/booking/holds/" + holdId + "/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"paymentRef\":\"" + payment + "\"}");
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    /** Creates a hold on A1 of a fresh show and returns its id. */
    private String holdId(String show) throws Exception {
        String body = hold(show, "[\"A1\"]").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.holdId");
    }

    /** Proves a hold returns 201 with the hold id, sorted seats and an expiry five minutes ahead. */
    @Test
    void holdReturns201() throws Exception {
        String expires = clock.instant().plus(Duration.ofMinutes(5)).toString();
        hold(newShow("t-hold"), "[\"A2\",\"A1\"]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").exists())
                .andExpect(jsonPath("$.seatIds[0]").value("A1"))
                .andExpect(jsonPath("$.expiresAt").value(expires));
    }

    /** Proves a seat conflict is a 409 problem detail listing every unavailable seat, and nothing was held. */
    @Test
    void seatConflictReturns409WithTheSeats() throws Exception {
        String show = newShow("t-conflict");
        hold(show, "[\"A2\",\"A3\"]").andExpect(status().isCreated());
        hold(show, "[\"A1\",\"A2\",\"A3\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.unavailableSeats[0]").value("A2"))
                .andExpect(jsonPath("$.unavailableSeats[1]").value("A3"));
        hold(show, "[\"A1\"]").andExpect(status().isCreated());
    }

    /** Proves bad hold requests are told apart: unknown show 404, unknown seat 400, empty list 400. */
    @Test
    void badHoldRequests() throws Exception {
        hold("no-such-show", "[\"A1\"]").andExpect(status().isNotFound());
        String show = newShow("t-bad");
        hold(show, "[\"Z9\"]").andExpect(status().isBadRequest());
        hold(show, "[]").andExpect(status().isBadRequest());
        hold(show, "[\"A1\",\"A1\"]").andExpect(status().isBadRequest());
    }

    /** Proves confirm without the Idempotency-Key header is a 400. */
    @Test
    void missingKeyIs400() throws Exception {
        String id = holdId(newShow("t-nokey"));
        confirm(id, null, "pay-1").andExpect(status().isBadRequest());
    }

    /** Proves the first confirm is 201 and a replay with the same key and body is 200 with the same booking id. */
    @Test
    void firstConfirmIs201AndReplayIs200WithTheSameBody() throws Exception {
        String id = holdId(newShow("t-replay"));
        String first = confirm(id, "k1", "pay-1").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = confirm(id, "k1", "pay-1").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
    }

    /** Proves the same key with a different body is a 422, as the IETF Idempotency-Key draft asks; 409 is kept for a booked hold. */
    @Test
    void sameKeyDifferentBodyIs422() throws Exception {
        String id = holdId(newShow("t-keyconflict"));
        confirm(id, "k2", "pay-1").andExpect(status().isCreated());
        confirm(id, "k2", "pay-2").andExpect(status().isUnprocessableContent());
    }

    /** Proves a hold that has reached its expiry instant answers 410 Gone, and one nanosecond earlier it still confirms. */
    @Test
    void expiredHoldIs410() throws Exception {
        String early = holdId(newShow("t-expiry-a"));
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        confirm(early, "k3", "pay-1").andExpect(status().isCreated());
        String late = holdId(newShow("t-expiry-b"));
        clock.advance(Duration.ofMinutes(5));
        confirm(late, "k4", "pay-2").andExpect(status().isGone());
    }

    /** Proves an unknown hold id is a 404 for both confirm and delete. */
    @Test
    void unknownHoldIs404() throws Exception {
        confirm("hold-none", "k5", "pay").andExpect(status().isNotFound());
        mvc.perform(delete("/api/booking/holds/hold-none")).andExpect(status().isNotFound());
    }

    /** Proves DELETE releases a hold with 204, is repeatable, and frees the seat for a new hold. */
    @Test
    void deleteReleasesWith204() throws Exception {
        String show = newShow("t-delete");
        String id = holdId(show);
        mvc.perform(delete("/api/booking/holds/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/booking/holds/" + id)).andExpect(status().isNoContent());
        hold(show, "[\"A1\"]").andExpect(status().isCreated());
    }

    /** Proves confirming an already-booked hold with a NEW key is a 409, not a second booking. */
    @Test
    void confirmingABookedHoldWithANewKeyIs409() throws Exception {
        String id = holdId(newShow("t-newkey"));
        confirm(id, "k7", "pay").andExpect(status().isCreated());
        confirm(id, "k8", "pay").andExpect(status().isConflict());
    }

    /** Proves missing or blank bodies are 400: no paymentRef, a blank paymentRef, and null seatIds. */
    @Test
    void missingBodyFieldsAre400() throws Exception {
        String id = holdId(newShow("t-nobody"));
        mvc.perform(post("/api/booking/holds/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k9").content("{}"))
                .andExpect(status().isBadRequest());
        confirm(id, "k9", " ").andExpect(status().isBadRequest());
        mvc.perform(post("/api/booking/shows/t-nobody/holds").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Proves a booked hold cannot be deleted (409). */
    @Test
    void deleteOfBookedHoldIs409() throws Exception {
        String id = holdId(newShow("t-delbooked"));
        confirm(id, "k6", "pay").andExpect(status().isCreated());
        mvc.perform(delete("/api/booking/holds/" + id)).andExpect(status().isConflict());
    }
}
