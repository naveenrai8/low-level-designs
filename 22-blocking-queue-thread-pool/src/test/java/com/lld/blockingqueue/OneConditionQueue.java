package com.lld.blockingqueue;

import com.lld.blockingqueue.QueueHooks.Side;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BUG CLASS. A bounded queue with correct {@code while} loops but only ONE waiting room shared by takers and putters, woken with {@code signal()}.
 *
 * <p>{@code signal()} wakes one thread, and with one room it may be the wrong kind. A taker that finishes can wake another taker (who sees an empty queue and sleeps again) while a putter that needed the wakeup sleeps forever. The wakeup is lost.
 *
 * <p>The real queue avoids this with two rooms ({@code notEmpty} for takers, {@code notFull} for putters), so each signal always reaches the right kind.
 */
final class OneConditionQueue implements SimpleQueue<String> {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition room = lock.newCondition();
    private final QueueHooks hooks;
    private final String[] items;
    private int head;
    private int count;

    /** Creates the queue with the given capacity. */
    OneConditionQueue(int capacity, QueueHooks hooks) {
        this.items = new String[capacity];
        this.hooks = hooks;
    }

    /** Adds an item, waiting in the shared room while full, then wakes one waiter of either kind. */
    @Override
    public void put(String e) throws InterruptedException {
        lock.lock();
        try {
            while (count == items.length) {
                hooks.beforeAwait(Side.NOT_FULL);
                room.await();
            }
            items[(head + count) % items.length] = e;
            count++;
            room.signal();
            hooks.afterSignal(Side.NOT_EMPTY);
        } finally {
            lock.unlock();
        }
    }

    /** Takes an item, waiting in the shared room while empty, then wakes one waiter of either kind. */
    @Override
    public String take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                hooks.beforeAwait(Side.NOT_EMPTY);
                room.await();
            }
            String e = items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            room.signal();
            hooks.afterSignal(Side.NOT_FULL);
            return e;
        } finally {
            lock.unlock();
        }
    }

    /** Not needed by the lost-signal test. */
    @Override
    public String poll() {
        throw new UnsupportedOperationException();
    }
}
