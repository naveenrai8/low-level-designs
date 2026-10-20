package com.learning.university.model;

import java.util.concurrent.atomic.AtomicInteger;

public class Course {
    private final String courseCode;
    private final String name;
    private final String instructor;
    private final int maxCapacity;
    private final AtomicInteger enrolled;

    public Course(String courseCode, String name, String instructor, int maxCapacity) {
        this.courseCode = courseCode;
        this.name = name;
        this.instructor = instructor;
        this.maxCapacity = maxCapacity;
        this.enrolled = new AtomicInteger(0);
    }

    public boolean register() {
        return enrolled.updateAndGet(current ->
            current < maxCapacity ? current + 1 : current
        ) <= maxCapacity;
    }

    public void drop() {
        enrolled.updateAndGet(current -> Math.max(0, current - 1));
    }

    public boolean isFull() {
        return enrolled.get() >= maxCapacity;
    }

    public String getCourseCode() { return courseCode; }
    public String getName() { return name; }
    public int getEnrolled() { return enrolled.get(); }
    public int getAvailable() { return maxCapacity - enrolled.get(); }
}
