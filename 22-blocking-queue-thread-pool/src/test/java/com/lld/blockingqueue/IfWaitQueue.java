package com.lld.blockingqueue;

import com.lld.blockingqueue.QueueHooks.Side;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BUG CLASS. An unbounded-looking queue whose taker waits inside an {@code if} instead of a {@code while}, and whose put wakes everyone ({@code signalAll}).
 *
 * <p>After waking, a taker assumes "someone put an item, so there is one for me". That is false when another woken taker (or a thread that sneaked in) already took it. The taker then removes from an empty queue and gets {@code null}.
 *
 * <p>Passing {@code checkInLoop = true} switches the {@code if} to a {@code while}. That is the only difference, so the same test can show the bug and the fix.
 */
final class IfWaitQueue implements SimpleQueue<String> {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final QueueHooks hooks;
    private final boolean checkInLoop;
    private final String[] items = new String[16];
    private int head;
    private int count;

    /** Creates the queue. {@code checkInLoop = false} is the bug, {@code true} is the fix. */
    IfWaitQueue(boolean checkInLoop, QueueHooks hooks) {
        this.checkInLoop = checkInLoop;
        this.hooks = hooks;
    }

    /** Adds an item and wakes ALL waiting takers, like a spurious or broadcast wakeup would. */
    @Override
    public void put(String e) {
        lock.lock();
        try {
            items[(head + count) % items.length] = e;
            count++;
            notEmpty.signalAll();
            hooks.afterSignal(Side.NOT_EMPTY);
        } finally {
            lock.unlock();
        }
    }

    /** Takes an item. The buggy version waits once and then trusts that an item exists. */
    @Override
    public String take() throws InterruptedException {
        lock.lock();
        try {
            if (checkInLoop) {
                while (count == 0) {
                    hooks.beforeAwait(Side.NOT_EMPTY);
                    notEmpty.await();
                }
            } else if (count == 0) {
                hooks.beforeAwait(Side.NOT_EMPTY);
                notEmpty.await();
            }
            return removeHead();
        } finally {
            lock.unlock();
        }
    }

    /** Takes an item only if one is there. */
    @Override
    public String poll() {
        lock.lock();
        try {
            return count == 0 ? null : removeHead();
        } finally {
            lock.unlock();
        }
    }

    /** Removes the head slot. On an empty queue this returns null and pushes the count below zero, which is the visible damage of the bug. */
    private String removeHead() {
        String e = items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        count--;
        return e;
    }
}
