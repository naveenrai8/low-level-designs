package com.lld.mistakes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * <b>Fix (M7).</b>
 * <ul>
 *   <li>{@code CopyOnWriteArrayList}: iteration runs over a snapshot, so subscribing or
 *       unsubscribing during dispatch is safe. This is the case COW is <em>for</em>: tiny, rarely
 *       written, iterated constantly.</li>
 *   <li>Each listener gets its own try/catch, and failures go to an injected error handler (log
 *       + metric). They're never silently swallowed and never rethrown into the publisher.</li>
 * </ul>
 * Still synchronous: a slow listener delays the publisher. Moving dispatch to an executor fixes
 * that and brings ordering and backpressure questions with it (Topic 09, Topic 25).
 */
public final class IsolatingEventBus<E> implements EventBus<E> {

    private final List<Consumer<? super E>> listeners = new CopyOnWriteArrayList<>();
    private final BiConsumer<? super E, RuntimeException> onListenerError;

    public IsolatingEventBus(BiConsumer<? super E, RuntimeException> onListenerError) {
        this.onListenerError = onListenerError;
    }

    @Override
    public Runnable subscribe(Consumer<? super E> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publish(E event) {
        for (Consumer<? super E> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {             // Errors (OOM etc.) still propagate
                onListenerError.accept(event, e);
            }
        }
    }
}
