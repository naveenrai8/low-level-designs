package com.lld.connpool;

/**
 * A snapshot of the pool's counters, taken under the pool lock so the numbers agree with each other.
 *
 * @param idle     connections sitting free in the pool
 * @param borrowed connections currently handed out
 * @param total    every live connection, including ones being opened or closed right now ({@code total <= max} always)
 * @param waiting  borrowers parked and waiting for a connection
 */
public record Stats(int idle, int borrowed, int total, int waiting) {
}
