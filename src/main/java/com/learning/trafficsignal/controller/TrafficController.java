package com.learning.trafficsignal.controller;

import com.learning.trafficsignal.model.TrafficSignal;
import com.learning.trafficsignal.service.IntersectionController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/traffic")
public class TrafficController {
    private final IntersectionController controller;

    public TrafficController(IntersectionController controller) {
        this.controller = controller;
    }

    @GetMapping("/status")
    public ResponseEntity<List<TrafficSignal>> getStatus() {
        return ResponseEntity.ok(controller.getSignalStatus());
    }

    @PostMapping("/emergency")
    public ResponseEntity<String> handleEmergency(@RequestParam String direction) {
        controller.handleEmergency(direction);
        return ResponseEntity.ok("Emergency mode activated for " + direction);
    }

    @PostMapping("/emergency/clear")
    public ResponseEntity<String> clearEmergency() {
        controller.clearEmergency();
        return ResponseEntity.ok("Emergency mode cleared");
    }
}
