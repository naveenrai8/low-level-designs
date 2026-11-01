package com.lld.elevator;

/** One simulated person: shows up at {@code origin} on tick {@code arrival} and wants to go to {@code dest}. */
public record Rider(int id, long arrival, int origin, int dest) {
}
