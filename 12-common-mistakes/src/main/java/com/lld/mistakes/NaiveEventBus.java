package com.lld.mistakes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>Bug (M7).</b> Two classic defects in one loop:
 * <ul>
 *   <li>One listener throwing stops every listener after it, and the exception escapes into the
 *       <em>publisher</em>, so a broken analytics hook fails a checkout.</li>
 *   <li>A listener that unsubscribes itself during dispatch mutates the list being iterated, even
 *       on a single thread. Usually that throws {@code ConcurrentModificationException}. But if it
 *       happens at the second-to-last listener, the iterator just stops and the last listener is
 *       <em>silently skipped</em>.</li>
 * </ul>
 */
public final class NaiveEventBus<E> implements EventBus<E> {

    private final List<Consumer<? super E>> listeners = new ArrayList<>();

    @Override
    public Runnable subscribe(Consumer<? super E> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(E event) {
        for (Consumer<? super E> listener : listeners) {
            listener.accept(event);                    // BUG: no isolation; list mutable mid-iteration
        }
    }
}
