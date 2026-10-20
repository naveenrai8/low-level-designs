package com.learning.university.service;

import com.learning.university.model.Course;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CourseRegistrationService {
    private final ConcurrentHashMap<String, Course> courses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> studentCourses = new ConcurrentHashMap<>();

    public CourseRegistrationService() {
        initializeCourses();
    }

    private void initializeCourses() {
        courses.put("CS101", new Course("CS101", "Intro to CS", "Prof. Smith", 50));
        courses.put("CS201", new Course("CS201", "Data Structures", "Prof. Johnson", 40));
        courses.put("CS301", new Course("CS301", "Algorithms", "Prof. Williams", 30));
    }

    public synchronized boolean registerCourse(String studentId, String courseCode) throws Exception {
        Course course = courses.get(courseCode);
        if (course == null) {
            throw new Exception("Course not found");
        }

        if (course.isFull()) {
            throw new Exception("Course is full");
        }

        Set<String> registered = studentCourses.computeIfAbsent(studentId, k -> ConcurrentHashMap.newKeySet());

        if (registered.contains(courseCode)) {
            throw new Exception("Already registered");
        }

        if (course.register()) {
            registered.add(courseCode);
            return true;
        }

        throw new Exception("Registration failed");
    }

    public Collection<Course> searchCourses(String query) {
        return courses.values().stream()
            .filter(c -> c.getCourseCode().contains(query) || c.getName().contains(query))
            .toList();
    }

    public Collection<Course> getAllCourses() {
        return courses.values();
    }
}
