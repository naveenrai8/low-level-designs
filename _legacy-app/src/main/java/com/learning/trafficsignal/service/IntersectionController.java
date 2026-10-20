package com.learning.trafficsignal.service;

import com.learning.trafficsignal.model.SignalState;
import com.learning.trafficsignal.model.TrafficSignal;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

@Service
public class IntersectionController {
    private final List<TrafficSignal> signals;
    private final ScheduledExecutorService scheduler;
    private volatile boolean emergencyMode = false;

    public IntersectionController() {
        this.signals = new ArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        initializeSignals();
        startAutomaticControl();
    }

    private void initializeSignals() {
        signals.add(new TrafficSignal("SIG-1", "NORTH"));
        signals.add(new TrafficSignal("SIG-2", "SOUTH"));
        signals.add(new TrafficSignal("SIG-3", "EAST"));
        signals.add(new TrafficSignal("SIG-4", "WEST"));
    }

    private void startAutomaticControl() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!emergencyMode) {
                signals.forEach(TrafficSignal::transition);
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    public void handleEmergency(String direction) {
        emergencyMode = true;
        for (TrafficSignal signal : signals) {
            if (signal.getDirection().equals(direction)) {
                signal.setState(SignalState.GREEN);
            } else {
                signal.setState(SignalState.RED);
            }
        }
    }

    public void clearEmergency() {
        emergencyMode = false;
    }

    public List<TrafficSignal> getSignalStatus() {
        return new ArrayList<>(signals);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
