package com.lld.blockingqueue;

/** The three operations the wakeup tests need, so the same test can run against the real queue and against the deliberately broken ones. */
interface SimpleQueue<E> {

    /** Adds an item, waiting while full. */
    void put(E e) throws InterruptedException;

    /** Removes an item, waiting while empty. */
    E take() throws InterruptedException;

    /** Removes an item if one is there right now, else returns null. Never waits. */
    E poll();

    /** Adapts the real queue to this small interface. */
    static <E> SimpleQueue<E> of(BoundedQueue<E> q) {
        return new SimpleQueue<>() {
            /** Forwards to the real queue. */
            @Override
            public void put(E e) throws InterruptedException {
                q.put(e);
            }

            /** Forwards to the real queue. */
            @Override
            public E take() throws InterruptedException {
                return q.take();
            }

            /** Forwards to the real queue. */
            @Override
            public E poll() {
                return q.poll();
            }
        };
    }
}
