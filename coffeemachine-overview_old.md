# Coffee Vending Machine System - Implementation Overview

## Design Patterns
- **Builder Pattern**: Coffee recipe construction
- **Factory Pattern**: Coffee creation
- **Strategy Pattern**: Different brewing strategies

## Coffee Types & Recipes
- **Espresso**: Water(30ml), Coffee(18g) - $2.50
- **Cappuccino**: Water(30ml), Milk(60ml), Coffee(18g) - $3.50
- **Latte**: Water(30ml), Milk(120ml), Coffee(18g) - $4.00

## Key Components
- `CoffeeType`: Enum of available coffees
- `Recipe`: Ingredients and quantities
- `Ingredient`: Enum (WATER, MILK, COFFEE, SUGAR)
- `InventoryManager`: Thread-safe ingredient tracking
- `CoffeeMaker`: Brewing service

## Features
- Ingredient inventory with low-stock alerts
- Concurrent order processing
- Payment integration
- Recipe management
- Automatic stock deduction
