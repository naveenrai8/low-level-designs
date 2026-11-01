package com.lld.connpool;

/**
 * One suspected leak: a connection that has been borrowed for too long.
 *
 * @param connectionId the id of the connection that was never given back
 * @param heldNanos    how long it has been out, by the pool's ticker
 * @param threadName   the name of the thread that borrowed it
 * @param borrowStack  where in the code it was borrowed, or {@code null} if stack capture is off
 */
public record Leak(long connectionId, long heldNanos, String threadName, Throwable borrowStack) {
}
