package com.learning.carrental.model;

public class Car {
    private final String licensePlate;
    private final String make;
    private final String model;
    private final int year;
    private final double dailyRate;
    private volatile CarStatus status;

    public Car(String licensePlate, String make, String model, int year, double dailyRate) {
        this.licensePlate = licensePlate;
        this.make = make;
        this.model = model;
        this.year = year;
        this.dailyRate = dailyRate;
        this.status = CarStatus.AVAILABLE;
    }

    public synchronized boolean reserve() {
        if (status == CarStatus.AVAILABLE) {
            status = CarStatus.RESERVED;
            return true;
        }
        return false;
    }

    public synchronized void release() {
        status = CarStatus.AVAILABLE;
    }

    public String getLicensePlate() { return licensePlate; }
    public String getMake() { return make; }
    public String getModel() { return model; }
    public int getYear() { return year; }
    public double getDailyRate() { return dailyRate; }
    public CarStatus getStatus() { return status; }
}
