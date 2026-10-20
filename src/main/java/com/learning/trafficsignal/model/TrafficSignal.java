package com.learning.trafficsignal.model;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

public class TrafficSignal {
    private final String id;
    private final String direction;
    private volatile SignalState currentState;
    private volatile Instant lastTransition;
    private final ReentrantLock lock = new ReentrantLock();

    public TrafficSignal(String id, String direction) {
        this.id = id;
        this.direction = direction;
        this.currentState = SignalState.RED;
        this.lastTransition = Instant.now();
    }

    public void transition() {
        lock.lock();
        try {
            currentState = currentState.next();
            lastTransition = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    public void setState(SignalState state) {
        lock.lock();
        try {
            currentState = state;
            lastTransition = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    public String getId() { return id; }
    public String getDirection() { return direction; }
    public SignalState getCurrentState() { return currentState; }
    public Instant getLastTransition() { return lastTransition; }
}
