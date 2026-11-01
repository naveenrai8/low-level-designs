package com.lld.dispatch;

/**
 * The answer to "who is nearest": the driver and the squared distance to them.
 *
 * @param driverId the driver found
 * @param distSq the exact squared distance in square metres
 */
public record Match(long driverId, long distSq) {
}
