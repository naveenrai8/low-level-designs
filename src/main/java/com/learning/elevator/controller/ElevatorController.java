package com.learning.elevator.controller;

import com.learning.elevator.model.Elevator;
import com.learning.elevator.service.ElevatorSystem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/elevator")
public class ElevatorController {
    private final ElevatorSystem elevatorSystem;

    public ElevatorController(ElevatorSystem elevatorSystem) {
        this.elevatorSystem = elevatorSystem;
    }

    @PostMapping("/request")
    public ResponseEntity<?> requestElevator(
            @RequestParam int fromFloor,
            @RequestParam int toFloor) {
        Elevator elevator = elevatorSystem.requestElevator(fromFloor, toFloor);
        return ResponseEntity.ok(Map.of(
            "elevatorId", elevator.getId(),
            "currentFloor", elevator.getCurrentFloor(),
            "message", "Elevator " + elevator.getId() + " dispatched"
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(elevatorSystem.getAllElevators());
    }
}
