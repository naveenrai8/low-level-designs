package com.learning.elevator.model;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class Elevator {
    private final int id;
    private final int capacity;
    private volatile int currentFloor;
    private volatile Direction direction;
    private volatile int currentLoad;
    private final PriorityBlockingQueue<Integer> upRequests;
    private final PriorityBlockingQueue<Integer> downRequests;
    private final ReentrantLock lock = new ReentrantLock();

    public Elevator(int id, int capacity) {
        this.id = id;
        this.capacity = capacity;
        this.currentFloor = 0;
        this.direction = Direction.IDLE;
        this.currentLoad = 0;
        this.upRequests = new PriorityBlockingQueue<>();
        this.downRequests = new PriorityBlockingQueue<>(11, (a, b) -> b - a);
    }

    public void addRequest(int floor) {
        if (floor > currentFloor) {
            upRequests.offer(floor);
        } else if (floor < currentFloor) {
            downRequests.offer(floor);
        }
    }

    public void moveToNextFloor() {
        lock.lock();
        try {
            if (direction == Direction.UP && !upRequests.isEmpty()) {
                Integer next = upRequests.poll();
                if (next != null) currentFloor = next;
            } else if (direction == Direction.DOWN && !downRequests.isEmpty()) {
                Integer next = downRequests.poll();
                if (next != null) currentFloor = next;
            } else {
                direction = Direction.IDLE;
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean canAccommodate(int passengers) {
        return currentLoad + passengers <= capacity;
    }

    public int getId() { return id; }
    public int getCurrentFloor() { return currentFloor; }
    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }
}
