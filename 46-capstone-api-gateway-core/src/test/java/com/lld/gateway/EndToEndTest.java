package com.lld.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.resilience.CircuitState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The capstone scenario: one client journey through every step, on virtual time, with the exact status sequence asserted. */
class EndToEndTest {

    /** Walks 200, 401, 403, 429, 503, then a recovery 200, and checks how many times each upstream was really called. */
    @Test
    void fullJourneyThroughAllThreeRounds() {
        Fixture f = new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)));
        List<Integer> statuses = new ArrayList<>();

        statuses.add(f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());
        statuses.add(f.gateway.handle(GatewayRequest.of("GET", "/orders/1")).status());
        statuses.add(f.gateway.handle(Fixture.post("/orders", "key-bob")).status());
        for (int i = 0; i < 2; i++) {
            f.gateway.handle(Fixture.get("/orders/1", "key-alice"));
        }
        statuses.add(f.gateway.handle(Fixture.get("/orders/1", "key-alice")).status());

        f.billing.always(500);
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        f.gateway.handle(Fixture.get("/billing", "key-erin"));
        statuses.add(f.gateway.handle(Fixture.get("/billing", "key-erin")).status());

        f.billing.always(200);
        f.clock.advance(Duration.ofSeconds(30));
        statuses.add(f.gateway.handle(Fixture.get("/billing", "key-erin")).status());

        assertEquals(List.of(200, 401, 403, 429, 503, 200), statuses);
        assertEquals(3, f.orders.calls());
        assertEquals(3, f.billing.calls());
        assertEquals(CircuitState.CLOSED, f.gateway.breakerState("billing"));
        assertEquals(0, f.gateway.breakerMetrics("orders").failures());
    }
}
