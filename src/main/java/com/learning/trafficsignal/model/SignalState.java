package com.learning.trafficsignal.model;

public enum SignalState {
    RED(30),
    YELLOW(5),
    GREEN(60);

    private final int defaultDurationSeconds;

    SignalState(int defaultDurationSeconds) {
        this.defaultDurationSeconds = defaultDurationSeconds;
    }

    public int getDefaultDuration() {
        return defaultDurationSeconds;
    }

    public SignalState next() {
        return switch (this) {
            case RED -> GREEN;
            case GREEN -> YELLOW;
            case YELLOW -> RED;
        };
    }
}
