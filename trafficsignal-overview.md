# Traffic Signal Control System - Implementation Overview

## Design Pattern
- **State Machine Pattern**: RED → GREEN → YELLOW → RED
- **Observer Pattern**: Notify on state changes
- **Strategy Pattern**: Different timing strategies

## Key Components
- `SignalState`: RED, YELLOW, GREEN
- `TrafficSignal`: Individual signal with duration
- `IntersectionController`: Manages multiple signals
- `EmergencyHandler`: Override for emergency vehicles

## State Transitions
1. RED (30s) → GREEN (60s) → YELLOW (5s) → RED
2. Emergency: All RED except emergency route GREEN
3. Configurable durations per signal

## Thread-Safety
- `ScheduledExecutorService` for timed transitions
- `ReentrantLock` for state changes
- Atomic state updates

## Features
- Multi-road intersection support
- Configurable signal durations
- Emergency vehicle priority
- Real-time status monitoring
