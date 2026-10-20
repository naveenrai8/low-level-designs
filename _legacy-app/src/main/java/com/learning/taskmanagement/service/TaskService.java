package com.learning.taskmanagement.service;

import com.learning.taskmanagement.model.Priority;
import com.learning.taskmanagement.model.Task;
import com.learning.taskmanagement.model.TaskStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class TaskService {
    private final ConcurrentHashMap<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong taskIdCounter = new AtomicLong(0);

    public Task createTask(String title, String description, Instant dueDate,
                          Priority priority, Long createdBy) {
        Long id = taskIdCounter.incrementAndGet();
        Task task = new Task(id, title, description, dueDate, priority, createdBy);
        tasks.put(id, task);
        return task;
    }

    public Optional<Task> getTask(Long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public void updateTask(Long id, String title, String description,
                          Instant dueDate, Priority priority) throws Exception {
        Task task = tasks.get(id);
        if (task == null) throw new Exception("Task not found");
        task.update(title, description, dueDate, priority);
    }

    public void deleteTask(Long id) {
        tasks.remove(id);
    }

    public void assignTask(Long taskId, Long userId) throws Exception {
        Task task = tasks.get(taskId);
        if (task == null) throw new Exception("Task not found");
        task.assignTo(userId);
    }

    public void updateStatus(Long taskId, TaskStatus status) throws Exception {
        Task task = tasks.get(taskId);
        if (task == null) throw new Exception("Task not found");
        task.setStatus(status);
    }

    public List<Task> filterTasks(Priority priority, TaskStatus status, Long assignedTo) {
        return tasks.values().stream()
                .filter(t -> priority == null || t.getPriority() == priority)
                .filter(t -> status == null || t.getStatus() == status)
                .filter(t -> assignedTo == null || Objects.equals(t.getAssignedTo(), assignedTo))
                .collect(Collectors.toList());
    }

    public List<Task> getTasksByUser(Long userId) {
        return tasks.values().stream()
                .filter(t -> Objects.equals(t.getCreatedBy(), userId) ||
                            Objects.equals(t.getAssignedTo(), userId))
                .collect(Collectors.toList());
    }

    public List<Task> getOverdueTasks() {
        Instant now = Instant.now();
        return tasks.values().stream()
                .filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(now))
                .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                .collect(Collectors.toList());
    }
}
