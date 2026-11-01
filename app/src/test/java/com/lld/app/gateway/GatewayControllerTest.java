package com.lld.app.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lld.gateway.GatewayRequest;
import com.lld.gateway.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** Full Spring context with virtual time and the demo backends: proves the HTTP mapping of 200, 401, 404, 429 and 503. Each test gets a fresh context so limiter and breaker state never leak between tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GatewayControllerTest.VirtualTime.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GatewayControllerTest {

    /** Replaces the gateway's clock with one that never moves, so no token refills and no breaker wait expires. */
    @TestConfiguration(proxyBeanMethods = false)
    static class VirtualTime {
        /** Returns a frozen clock under the gateway's own Ticker type. */
        @Bean
        @Primary
        Ticker frozenGatewayTicker() {
            return () -> 1_000L;
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBackends backends;

    /** Proves a valid key reaches the backend and its body comes back with 200. */
    @Test
    void validRequestIs200() throws Exception {
        mvc.perform(get("/gw/orders/42").header("X-API-Key", "key-alice"))
                .andExpect(status().isOk())
                .andExpect(content().string("orders handled GET /orders/42"));
    }

    /** Proves a missing key is 401 with WWW-Authenticate and the backend is untouched. */
    @Test
    void missingKeyIs401() throws Exception {
        mvc.perform(get("/gw/orders/42")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "ApiKey"));
        org.junit.jupiter.api.Assertions.assertEquals(0, backends.orders.calls());
    }

    /** Proves an unknown path is 404. */
    @Test
    void unknownPathIs404() throws Exception {
        mvc.perform(get("/gw/nope").header("X-API-Key", "key-alice")).andExpect(status().isNotFound());
    }

    /** Proves the 6th request in the window is 429 with Retry-After in whole seconds (the window is 1 minute, so 12 s per token). */
    @Test
    void sixthRequestIs429WithRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/gw/orders/1").header("X-API-Key", "key-alice")).andExpect(status().isOk());
        }
        mvc.perform(get("/gw/orders/1").header("X-API-Key", "key-alice"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"));
    }

    /** Proves a failing backend opens the breaker and the next request is 503 without reaching the backend. */
    @Test
    void openBreakerIs503() throws Exception {
        backends.billing.failing(true);
        for (int i = 0; i < 4; i++) {
            mvc.perform(get("/gw/billing").header("X-API-Key", "key-alice")).andExpect(status().is(500));
        }
        int before = backends.billing.calls();
        mvc.perform(get("/gw/billing").header("X-API-Key", "key-alice")).andExpect(status().isServiceUnavailable());
        org.junit.jupiter.api.Assertions.assertEquals(before, backends.billing.calls());
    }

    /** Proves the request body and the query string reach the upstream untouched. */
    @Test
    void bodyAndQueryReachTheUpstream() throws Exception {
        mvc.perform(post("/gw/orders?page=2").header("X-API-Key", "key-alice").content("{\"item\":1}")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals("{\"item\":1}", backends.orders.last().body());
        org.junit.jupiter.api.Assertions.assertEquals("page=2", backends.orders.last().query());
    }

    /** Proves hop-by-hop headers (and headers named by Connection) are dropped, the credential is stripped, and the gateway sets the client identity. */
    @Test
    void hopByHopAndCredentialHeadersAreNotForwarded() throws Exception {
        mvc.perform(get("/gw/orders/1").header("X-API-Key", "key-alice").header("Connection", "close, X-Secret").header("X-Secret", "s").header("Keep-Alive", "timeout=5").header("TE", "trailers").header("X-Client-Id", "admin").header("X-Keep", "yes"))
                .andExpect(status().isOk());
        GatewayRequest seen = backends.orders.last();
        org.junit.jupiter.api.Assertions.assertNull(seen.header("connection"));
        org.junit.jupiter.api.Assertions.assertNull(seen.header("x-secret"));
        org.junit.jupiter.api.Assertions.assertNull(seen.header("keep-alive"));
        org.junit.jupiter.api.Assertions.assertNull(seen.header("te"));
        org.junit.jupiter.api.Assertions.assertNull(seen.header("x-api-key"));
        org.junit.jupiter.api.Assertions.assertEquals("alice", seen.header("x-client-id"));
        org.junit.jupiter.api.Assertions.assertEquals("yes", seen.header("x-keep"));
    }

    /** Proves a body over the cap is 413 (cap is 1 MiB), and the upstream is untouched. */
    @Test
    void oversizedBodyIs413() throws Exception {
        mvc.perform(post("/gw/orders").header("X-API-Key", "key-alice").content("x".repeat(1_048_577))).andExpect(status().isPayloadTooLarge());
        org.junit.jupiter.api.Assertions.assertEquals(0, backends.orders.calls());
    }

    /** Proves a crash inside the core is a plain 500 "gateway error" from the adapter, caught around the core call only. */
    @Test
    void unexpectedCoreCrashIs500() throws Exception {
        backends.orders.crashing(true);
        mvc.perform(get("/gw/orders/1").header("X-API-Key", "key-alice")).andExpect(status().isInternalServerError()).andExpect(content().string("gateway error"));
    }

    /** Proves a wrong method is 405 with the Allow header through HTTP. */
    @Test
    void wrongMethodIs405WithAllow() throws Exception {
        mvc.perform(post("/gw/billing").header("X-API-Key", "key-alice")).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET, HEAD"));
    }
}
