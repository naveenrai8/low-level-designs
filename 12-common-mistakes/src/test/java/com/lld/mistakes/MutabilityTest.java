package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves that handing out internal lists breaks an invariant, and that snapshots and defensive copies fix it.
 */
class MutabilityTest {

    private static final LineItem PEN = new LineItem("pen", 150, 1);

    /**
     * Proves a caller can add a third line to a cart limited to two by editing the list that {@code items()} returned.
     */
    @Test
    void bugReturningTheInternalListLetsCallersBypassTheInvariant() {
        LeakyCart cart = new LeakyCart(2);
        cart.add(PEN);
        cart.add(PEN);
        assertThrows(IllegalStateException.class, () -> cart.add(PEN));

        cart.items().add(PEN);                           // straight past the max-items check
        assertEquals(3, cart.items().size());
    }

    /**
     * Proves the read-only wrapper blocks writes but still shows later changes, and iterating it while the cart changes throws.
     */
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

    /**
     * Proves the snapshot cannot be changed and does not show later changes to the cart.
     */
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

    /**
     * Proves the order record keeps its own copy of the list, so the caller's later edits do not change it.
     */
    @Test
    void fixRecordCopiesItsMutableComponent() {
        List<LineItem> source = new ArrayList<>(List.of(PEN));
        OrderSnapshot order = new OrderSnapshot("o-1", source);
        source.add(new LineItem("book", 2_000, 1));      // caller keeps mutating its own list

        assertEquals(1, order.items().size());
        assertEquals(150, order.totalMinor());
        assertThrows(UnsupportedOperationException.class, () -> order.items().clear());
    }

    /**
     * Proves the order record refuses a list that contains null.
     */
    @Test
    void fixRecordRejectsNullElements() {
        List<LineItem> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new OrderSnapshot("o-2", withNull));
    }
}
