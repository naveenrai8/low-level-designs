package com.lld.elevator;

/** What one simulation run measured. Waits are in ticks: from pressing the hall button to the car opening for that rider. */
public record SimResult(String dispatcher, int riders, double meanWait, long p95Wait, long maxWait, long endTick) {
}
