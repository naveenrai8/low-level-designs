# Vending Machine System - Implementation Overview

## Design
- **Products**: Each with ID, name, price, quantity
- **Payment**: Accepts coins (1, 5, 10, 25 cents) and notes ($1, $5, $10, $20)
- **Inventory**: Thread-safe product management
- **Change**: Calculates and dispenses change
- **State Machine**: IDLE → ACCEPTING_PAYMENT → DISPENSING → RETURNING_CHANGE

## Key Classes
- `Product`: Immutable product information
- `Money`: Denomination enum (PENNY, NICKEL, DIME, QUARTER, ONE_DOLLAR, etc.)
- `VendingMachineService`: Main service with state management
- `InventoryService`: Product inventory management
- `PaymentProcessor`: Payment and change calculation

## Concurrency
- `ReentrantLock` for state transitions
- `ConcurrentHashMap` for inventory
- Atomic operations for quantity updates

## API Endpoints
- POST `/api/vending/select/{productId}` - Select product
- POST `/api/vending/insert` - Insert money
- POST `/api/vending/dispense` - Complete purchase
- POST `/api/vending/cancel` - Cancel and return money
- GET `/api/vending/inventory` - View available products
