package com.learning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot Application for Learning Systems.
 *
 * This application implements 7 system design problems:
 * 1. Parking Lot
 * 2. Stack Overflow
 * 3. Vending Machine
 * 4. Logging Framework
 * 5. Traffic Signal Control System
 * 6. Coffee Vending Machine
 * 7. Task Management System
 *
 * All systems use in-memory data structures and are thread-safe.
 *
 * @author Staff Engineer
 * @version 1.0.0
 */
@SpringBootApplication
public class LearningSystemsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearningSystemsApplication.class, args);
    }
}
