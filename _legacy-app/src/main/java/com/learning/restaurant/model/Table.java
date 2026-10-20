package com.learning.restaurant.model;

public class Table {
    private final int tableNumber;
    private final int capacity;
    private volatile TableStatus status;

    public Table(int tableNumber, int capacity) {
        this.tableNumber = tableNumber;
        this.capacity = capacity;
        this.status = TableStatus.AVAILABLE;
    }

    public synchronized boolean reserve() {
        if (status == TableStatus.AVAILABLE) {
            status = TableStatus.RESERVED;
            return true;
        }
        return false;
    }

    public synchronized void occupy() {
        status = TableStatus.OCCUPIED;
    }

    public synchronized void free() {
        status = TableStatus.AVAILABLE;
    }

    public int getTableNumber() { return tableNumber; }
    public int getCapacity() { return capacity; }
    public TableStatus getStatus() { return status; }
}
