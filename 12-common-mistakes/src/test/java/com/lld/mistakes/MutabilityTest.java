package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutabilityTest {

    private static final LineItem PEN = new LineItem("pen", 150, 1);

    @Test
    void bugReturningTheInternalListLetsCallersBypassTheInvariant() {
        LeakyCart cart = new LeakyCart(2);
        cart.add(PEN);
        cart.add(PEN);
        assertThrows(IllegalStateException.class, () -> cart.add(PEN));

        cart.items().add(PEN);                           // straight past the max-items check
        assertEquals(3, cart.items().size());
    }

    @Test
    void bugUnmodifiableListIsALiveViewNotASnapshot() {
        LeakyCart cart = new LeakyCart(5);
        cart.add(PEN);
        List<LineItem> view = cart.itemsView();
        assertThrows(UnsupportedOperationException.class, () -> view.add(PEN));

        cart.add(PEN);
        assertEquals(2, view.size(), "the 'read-only' list the caller kept just changed");
        assertThrows(ConcurrentModificationException.class, () -> {
            for (LineItem ignored : view) {
                cart.add(PEN);
            }
        });
    }

    @Test
    void fixSnapshotIsUnmodifiableAndDetached() {
        Cart cart = new Cart(5);
        cart.add(PEN);
        List<LineItem> snapshot = cart.items();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(PEN));

        cart.add(PEN);
        assertEquals(1, snapshot.size());
        assertEquals(2, cart.items().size());
    }

    @Test
    void fixRecordCopiesItsMutableComponent() {
        List<LineItem> source = new ArrayList<>(List.of(PEN));
        OrderSnapshot order = new OrderSnapshot("o-1", source);
        source.add(new LineItem("book", 2_000, 1));      // caller keeps mutating its own list

        assertEquals(1, order.items().size());
        assertEquals(150, order.totalMinor());
        assertThrows(UnsupportedOperationException.class, () -> order.items().clear());
    }

    @Test
    void fixRecordRejectsNullElements() {
        List<LineItem> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new OrderSnapshot("o-2", withNull));
    }
}
