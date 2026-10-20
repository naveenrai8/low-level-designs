package com.lld.mistakes;

import java.util.function.Consumer;

/** In-process publish/subscribe (the Observer pattern). Topic 25 is the full pub-sub. */
public interface EventBus<E> {

    /** @return a handle that unsubscribes this listener. */
    Runnable subscribe(Consumer<? super E> listener);

    void publish(E event);
}
