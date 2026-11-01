package com.lld.vending;

/** The seam to a remote monitoring system. The machine only calls {@link #emit}; how events travel (HTTP, MQTT, a local queue) is somebody else's problem. */
public interface Telemetry {

    /** Receives one event. May throw: the machine catches it, because a broken network must never block a sale or a refund. */
    void emit(MachineEvent event);

    /** Returns a telemetry that drops everything, used when no monitoring is wired in. */
    static Telemetry none() {
        return event -> { };
    }
}
