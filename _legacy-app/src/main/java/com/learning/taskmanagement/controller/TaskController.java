package com.learning.taskmanagement.controller;

import com.learning.taskmanagement.model.Priority;
import com.learning.taskmanagement.model.Task;
import com.learning.taskmanagement.model.TaskStatus;
import com.learning.taskmanagement.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Task> createTask(
            @RequestParam String title,
            @RequestParam String description,
            @RequestParam(required = false) Instant dueDate,
            @RequestParam Priority priority,
            @RequestParam Long createdBy) {
        Task task = service.createTask(title, description, dueDate, priority, createdBy);
        return ResponseEntity.ok(task);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> getTask(@PathVariable Long id) {
        return service.getTask(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<String> updateTask(
            @PathVariable Long id,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Instant dueDate,
            @RequestParam(required = false) Priority priority) {
        try {
            service.updateTask(id, title, description, dueDate, priority);
            return ResponseEntity.ok("Task updated");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteTask(@PathVariable Long id) {
        service.deleteTask(id);
        return ResponseEntity.ok("Task deleted");
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<String> assignTask(
            @PathVariable Long id,
            @RequestParam Long userId) {
        try {
            service.assignTask(id, userId);
            return ResponseEntity.ok("Task assigned");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<String> updateStatus(
            @PathVariable Long id,
            @RequestParam TaskStatus status) {
        try {
            service.updateStatus(id, status);
            return ResponseEntity.ok("Status updated");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/filter")
    public ResponseEntity<List<Task>> filterTasks(
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) Long assignedTo) {
        return ResponseEntity.ok(service.filterTasks(priority, status, assignedTo));
    }

    @GetMapping("/overdue")
    public ResponseEntity<List<Task>> getOverdueTasks() {
        return ResponseEntity.ok(service.getOverdueTasks());
    }
}
