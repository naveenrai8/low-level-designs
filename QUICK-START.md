# Quick Start Guide - All 7 Systems

## ⚡ Quick Commands

```bash
# 1. Set Java 21
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64
export PATH=$JAVA_HOME/bin:$PATH

# 2. Compile
mvn clean compile

# 3. Run
mvn spring-boot:run

# 4. Access
open http://localhost:8080/swagger-ui.html
```

## 📋 All API Endpoints

### System 1: Parking Lot
```bash
# Park a car
curl -X POST "http://localhost:8080/api/parking/entry" \
  -H "Content-Type: application/json" \
  -d '{"licensePlate":"ABC123","type":"CAR"}'

# Check availability
curl http://localhost:8080/api/parking/availability

# Exit vehicle (replace TKT-1 with actual ticket ID)
curl -X POST http://localhost:8080/api/parking/exit/TKT-1
```

### System 2: Stack Overflow
```bash
# Post a question
curl -X POST "http://localhost:8080/api/questions?authorId=1&title=How to use Java 21?&body=Need help&tags=java,spring"

# Search questions
curl "http://localhost:8080/api/questions/search?keywords=java"
```

### System 3: Vending Machine
```bash
# Select product
curl -X POST http://localhost:8080/api/vending/select/COKE

# Insert money
curl -X POST "http://localhost:8080/api/vending/insert?money=ONE_DOLLAR"

# Dispense
curl -X POST http://localhost:8080/api/vending/dispense
```

### System 4: Logging Framework
```java
// Use in Java code
Logger logger = LoggerFactory.getLogger(MyClass.class);
logger.info("Message");
logger.error("Error", exception);
```

### System 5: Traffic Signal
```bash
# Get status
curl http://localhost:8080/api/traffic/status

# Emergency mode
curl -X POST "http://localhost:8080/api/traffic/emergency?direction=NORTH"

# Clear emergency
curl -X POST http://localhost:8080/api/traffic/emergency/clear
```

### System 6: Coffee Machine
```bash
# Brew coffee
curl -X POST "http://localhost:8080/api/coffee/brew?type=LATTE&payment=5.0"

# Check inventory
curl http://localhost:8080/api/coffee/inventory

# Get low stock alerts
curl http://localhost:8080/api/coffee/alerts
```

### System 7: Task Management
```bash
# Create task
curl -X POST "http://localhost:8080/api/tasks?title=Fix bug&description=Critical&priority=HIGH&createdBy=1"

# Filter tasks
curl "http://localhost:8080/api/tasks/filter?status=PENDING&priority=HIGH"

# Get overdue tasks
curl http://localhost:8080/api/tasks/overdue
```

## 📊 Systems Overview

| # | System | Files | Features | Thread-Safe |
|---|--------|-------|----------|-------------|
| 1 | Parking Lot | 20+ | Multi-level, tickets, fees | ✅ ReentrantLock, ConcurrentHashMap |
| 2 | Stack Overflow | 8+ | Q&A, voting, tags, search | ✅ ConcurrentHashMap, Atomic |
| 3 | Vending Machine | 5+ | Products, payment, change | ✅ ReentrantLock, Atomic |
| 4 | Logging Framework | 7+ | Multi-level, appenders | ✅ CopyOnWriteArrayList |
| 5 | Traffic Signal | 5+ | State machine, emergency | ✅ ScheduledExecutor, Lock |
| 6 | Coffee Machine | 5+ | Recipes, inventory, alerts | ✅ ReentrantLock, Atomic |
| 7 | Task Management | 6+ | CRUD, filter, assignments | ✅ ReadWriteLock, Concurrent |

## 🎯 Key Features by System

### 1. Parking Lot
- ✅ 3 levels, 133 spots
- ✅ Motorcycle, Car, Truck support
- ✅ Fee: $5/hr (Motorcycle), $10/hr (Car), $20/hr (Truck)
- ✅ Full test suite with concurrency tests

### 2. Stack Overflow
- ✅ User reputation system
- ✅ +10 for answer upvote, +5 for question
- ✅ Search by keyword, tag, user
- ✅ Vote tracking with duplicates prevention

### 3. Vending Machine
- ✅ 4 products (Coke, Pepsi, Water, Chips)
- ✅ Coins: PENNY (1¢), NICKEL (5¢), DIME (10¢), QUARTER (25¢)
- ✅ Notes: $1, $5, $10, $20
- ✅ Automatic change calculation

### 4. Logging Framework
- ✅ 5 levels: DEBUG, INFO, WARN, ERROR, FATAL
- ✅ Console appender (extensible to File, DB)
- ✅ Thread-safe concurrent logging
- ✅ Factory pattern for logger instances

### 5. Traffic Signal
- ✅ 4 directions: NORTH, SOUTH, EAST, WEST
- ✅ Automatic transitions: RED(30s) → GREEN(60s) → YELLOW(5s)
- ✅ Emergency vehicle override
- ✅ Real-time status monitoring

### 6. Coffee Machine
- ✅ 3 types: Espresso ($2.50), Cappuccino ($3.50), Latte ($4.00)
- ✅ Ingredients: Water, Milk, Coffee, Sugar
- ✅ Low stock alerts (threshold: 100 units)
- ✅ Concurrent brewing with locks

### 7. Task Management
- ✅ Priorities: LOW, MEDIUM, HIGH, CRITICAL
- ✅ Status: PENDING, IN_PROGRESS, COMPLETED, CANCELLED
- ✅ Filter by priority, status, assignee, due date
- ✅ Overdue task detection
- ✅ Assignment and status updates

## 🛠️ Testing

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=ParkingLotIntegrationTest

# Run with coverage
mvn clean verify jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

## 📈 Code Statistics

- **Total Files**: 60+ Java files
- **Lines of Code**: ~4,000+
- **REST Endpoints**: 35+
- **Design Patterns**: 10+ (Facade, Strategy, Factory, Observer, State Machine, Builder, Singleton)
- **Compilation Status**: ✅ SUCCESS

## 🚀 Production Checklist

For production deployment, add:
- [ ] Database (PostgreSQL/MySQL) with JPA
- [ ] Redis caching
- [ ] Kafka/RabbitMQ for events
- [ ] Spring Security + JWT
- [ ] Rate limiting (Bucket4j)
- [ ] Circuit breakers (Resilience4j)
- [ ] Prometheus + Grafana monitoring
- [ ] Docker containers
- [ ] Kubernetes orchestration
- [ ] API Gateway

## 📚 Documentation

- `README.md` - Project overview
- `FINAL-SUMMARY.md` - Complete implementation summary
- `IMPLEMENTATION-STATUS.md` - Status tracker
- `parkinglot-explanation.md` - Detailed System 1 explanation
- `*-overview.md` - Overview docs for each system
- `*-testcase.md` - Test cases
- `*.puml` - PlantUML diagrams

## 💡 Tips

1. **Swagger UI is your friend**: Browse all APIs at http://localhost:8080/swagger-ui.html
2. **Health Check**: Monitor at http://localhost:8080/actuator/health
3. **Metrics**: View at http://localhost:8080/actuator/metrics
4. **Virtual Threads**: Enabled by default for high concurrency
5. **Logging**: Check console for structured logs with correlation IDs

## 🎉 Success!

All 7 systems are:
✅ Implemented
✅ Compiled
✅ Documented
✅ Ready to run

**Start now**: `mvn spring-boot:run`
