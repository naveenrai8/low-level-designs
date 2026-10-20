package com.learning.elevator.service;

import com.learning.elevator.model.Direction;
import com.learning.elevator.model.Elevator;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class ElevatorSystem {
    private final List<Elevator> elevators;
    private static final int NUM_ELEVATORS = 4;
    private static final int CAPACITY = 8;

    public ElevatorSystem() {
        elevators = new ArrayList<>();
        for (int i = 0; i < NUM_ELEVATORS; i++) {
            elevators.add(new Elevator(i + 1, CAPACITY));
        }
    }

    public Elevator requestElevator(int fromFloor, int toFloor) {
        Direction requestDirection = toFloor > fromFloor ? Direction.UP : Direction.DOWN;

        // Find closest elevator going in same direction or idle
        Elevator best = elevators.stream()
            .filter(e -> e.getDirection() == Direction.IDLE ||
                        e.getDirection() == requestDirection)
            .min((e1, e2) -> Integer.compare(
                Math.abs(e1.getCurrentFloor() - fromFloor),
                Math.abs(e2.getCurrentFloor() - fromFloor)
            ))
            .orElse(elevators.get(0));

        best.addRequest(fromFloor);
        best.addRequest(toFloor);
        best.setDirection(requestDirection);

        return best;
    }

    public List<Elevator> getAllElevators() {
        return new ArrayList<>(elevators);
    }
}
