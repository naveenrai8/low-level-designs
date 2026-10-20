package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {

    @Test
    void bugOneThrowingListenerStopsTheRestAndFailsThePublisher() {
        List<String> got = new ArrayList<>();
        EventBus<String> bus = new NaiveEventBus<>();
        bus.subscribe(e -> got.add("billing:" + e));
        bus.subscribe(e -> { throw new IllegalStateException("analytics down"); });
        bus.subscribe(e -> got.add("email:" + e));

        assertThrows(IllegalStateException.class, () -> bus.publish("order-1"));
        assertEquals(List.of("billing:order-1"), got, "email never ran");
    }

    @Test
    void bugListenerUnsubscribingItselfDuringDispatchSilentlySkipsTheNextListener() {
        // With 2 listeners there is no exception: after the removal, ArrayList's iterator sees
        // cursor == size, so hasNext() is false and the loop just ends. The last listener never runs.
        List<String> got = new ArrayList<>();
        EventBus<String> bus = new NaiveEventBus<>();
        Runnable[] handle = new Runnable[1];
        handle[0] = bus.subscribe(e -> { got.add("once:" + e); handle[0].run(); });
        bus.subscribe(e -> got.add("always:" + e));

        bus.publish("1");
        assertEquals(List.of("once:1"), got, "'always' was skipped, with no error anywhere");
    }

    @Test
    void bugWithThreeListenersTheSameUnsubscribeThrowsCme() {
        EventBus<String> bus = new NaiveEventBus<>();
        Runnable[] handle = new Runnable[1];
        handle[0] = bus.subscribe(e -> handle[0].run());
        bus.subscribe(e -> { });
        bus.subscribe(e -> { });

        assertThrows(ConcurrentModificationException.class, () -> bus.publish("x"));
    }

    @Test
    void fixEveryListenerRunsAndTheFailureIsReportedNotSwallowed() {
        List<String> got = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        EventBus<String> bus = new IsolatingEventBus<>((event, ex) -> errors.add(event + ":" + ex.getMessage()));
        bus.subscribe(e -> got.add("billing:" + e));
        bus.subscribe(e -> { throw new IllegalStateException("analytics down"); });
        bus.subscribe(e -> got.add("email:" + e));

        bus.publish("order-1");                          // does not throw
        assertEquals(List.of("billing:order-1", "email:order-1"), got);
        assertEquals(List.of("order-1:analytics down"), errors);
    }

    @Test
    void fixUnsubscribingDuringDispatchIsSafeAndTakesEffectNextPublish() {
        List<String> got = new ArrayList<>();
        EventBus<String> bus = new IsolatingEventBus<>((event, ex) -> { throw ex; });
        Runnable[] handle = new Runnable[1];
        handle[0] = bus.subscribe(e -> { got.add("once:" + e); handle[0].run(); });
        bus.subscribe(e -> got.add("always:" + e));

        bus.publish("1");
        bus.publish("2");
        assertEquals(List.of("once:1", "always:1", "always:2"), got);
    }
}
