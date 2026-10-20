# Logging Framework System - Implementation Overview

## Design Patterns
- **Strategy Pattern**: Different appenders (Console, File, Database)
- **Observer Pattern**: Multiple appenders can subscribe to logger
- **Singleton Pattern**: Logger instances per class
- **Factory Pattern**: LoggerFactory creates loggers

## Key Components
- `LogLevel`: DEBUG, INFO, WARN, ERROR, FATAL
- `LogMessage`: Timestamp, level, message, thread
- `Logger`: Main logging interface
- `LogAppender`: Interface for output destinations
  - `ConsoleAppender`
  - `FileAppender`
  - `DatabaseAppender` (simulated)
- `LoggerConfig`: Configuration management

## Thread-Safety
- `ConcurrentLinkedQueue` for async log queue
- `ReentrantLock` for file operations
- Virtual threads for async processing

## Features
- Hierarchical loggers
- Log level filtering
- Pattern-based formatting
- Async logging with queue
- Multiple simultaneous appenders
