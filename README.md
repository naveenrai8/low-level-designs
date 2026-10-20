# Learning Systems - Java 21 Implementation

This project implements 7 system design problems using Java 21, Spring Boot 3.2, and modern Java features.

## Systems Implemented

1. **Parking Lot** - Multi-level parking with vehicle management, ticketing, and fee calculation
2. **Stack Overflow** - Q&A platform with voting, tags, and search
3. **Vending Machine** - Product dispensing with payment and inventory management
4. **Logging Framework** - Multi-level logging with different output destinations
5. **Traffic Signal Control System** - Intersection traffic control with emergency handling
6. **Coffee Vending Machine** - Coffee dispensing with recipes and inventory
7. **Task Management System** - Task CRUD with assignments and reminders

## Technology Stack

- **Java 21** - Records, Sealed Classes, Pattern Matching, Virtual Threads
- **Spring Boot 3.2.1** - Web, Validation, Actuator
- **Maven** - Build tool
- **JUnit 5** - Testing framework
- **SLF4J + Logback** - Logging
- **Micrometer** - Metrics
- **SpringDoc OpenAPI** - API documentation

## Prerequisites

- Java 21 (OpenJDK 21.0.8 or later)
- Maven 3.8+

## Build Instructions

### Compile the project
```bash
mvn clean compile
```

### Run tests
```bash
mvn test
```

### Run with assertions enabled
```bash
mvn test -Darguments="-ea"
```

### Package the application
```bash
mvn clean package
```

### Run the application
```bash
mvn spring-boot:run
```

Or run the JAR:
```bash
java -jar target/learning-1.0.0-SNAPSHOT.jar
```

## API Documentation

Once the application is running, access:
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/v3/api-docs
- **Health Check**: http://localhost:8080/actuator/health

## Project Structure

```
src/main/java/com/learning/
├── parkinglot/          # System 1: Parking Lot
├── stackoverflow/       # System 2: Stack Overflow
├── vendingmachine/      # System 3: Vending Machine
├── logging/             # System 4: Logging Framework
├── trafficsignal/       # System 5: Traffic Signal Control
├── coffeemachine/       # System 6: Coffee Vending Machine
└── taskmanagement/      # System 7: Task Management

src/test/java/com/learning/
└── [same structure for tests]
```

## Design Principles

- **SOLID Principles** - Clean, maintainable code
- **Thread-Safe** - All systems handle concurrent access
- **In-Memory Only** - Using ConcurrentHashMap, thread-safe collections
- **Production-Ready** - Proper error handling, logging, validation
- **Modern Java** - Leveraging Java 21 features

## Testing

Each system includes:
- Unit tests for individual components
- Integration tests for full workflows
- Concurrency tests for thread-safety verification

## Notes

- All systems use in-memory data structures (no database)
- Thread-safe by design using java.util.concurrent
- REST APIs with proper validation and error handling
- Structured logging with correlation IDs
- Metrics hooks for observability

## Author

Senior/Staff-Level Java Engineer
