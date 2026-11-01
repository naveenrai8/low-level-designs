package com.lld.dispatch;

/**
 * A rider asking for a pickup.
 *
 * @param id the request id, unique per dispatcher
 * @param pickup where the rider is waiting
 */
public record Request(long id, Point pickup) {
}
