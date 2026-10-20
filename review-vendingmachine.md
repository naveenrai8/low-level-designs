# Vending Machine System - Code Review

## Overall Rating: D (60/100)

### Rating Breakdown
- **SOLID Principles**: C (72/100)
- **Concurrency**: D (55/100) - CRITICAL BUGS
- **Design Patterns**: C (70/100) - State Machine Pattern
- **Code Quality**: D (58/100)
- **Maintainability**: C (68/100)

---

## Executive Summary

The Vending Machine system demonstrates basic state machine concepts but has **CRITICAL bugs** in quantity checking, uses a singleton state that prevents concurrent usage, suffers from floating-point precision issues with money, and lacks proper change-making logic. Not suitable for production use without major refactoring.

---

## Strengths

### 1. Good Use of ReentrantLock for State Management ✅
**Location**: `service/VendingMachineService.java:13, 29-38`

```java
private final ReentrantLock stateLock = new ReentrantLock();

public void selectProduct(String productId) throws Exception {
    stateLock.lock();
    try {
        // State modification
    } finally {
        stateLock.unlock();
    }
}
```

Proper lock management with try-finally pattern.

### 2. AtomicInteger for Product Quantity ✅
**Location**: `model/Product.java:9`

```java
private final AtomicInteger quantity;
```

Good choice for thread-safe quantity tracking.

### 3. Clear State Machine Flow ✅
**Location**: `service/VendingMachineService.java`

Clear flow: Select → Insert Money → Dispense/Cancel

### 4. Immutable Product Properties ✅
**Location**: `model/Product.java:6-8`

```java
private final String id;
private final String name;
private final double price;
```

Core properties are final and immutable.

---

## Critical Issues

### 🔴 ISSUE #1: INCORRECT decrementQuantity() Logic - Always Returns True (CRITICAL)

**Location**: `model/Product.java:22-24`

**Problem**: Return value is meaningless, always returns true/false incorrectly

```java
public boolean decrementQuantity() {
    return quantity.updateAndGet(current ->
        current > 0 ? current - 1 : current
    ) >= 0;  // ❌ ALWAYS TRUE! Even when quantity is 0
}
```

**Root Cause**: The lambda returns either `current - 1` (positive) or `current` (0 or positive), so the result is **always >= 0**

**Why It's Wrong**:
```java
// Product has 1 item
product.decrementQuantity();  // Returns true (1-1=0, 0>=0 is true) ✅ Correct

// Product now has 0 items
product.decrementQuantity();  // Returns true (0>=0, no decrement) ❌ WRONG! Should be false!

// Multiple threads can "succeed" even with 0 stock
// Thread 1: decrementQuantity() returns true (stock: 0)
// Thread 2: decrementQuantity() returns true (stock: 0)  // ❌ BOTH succeed!
```

**Correct Implementation**:
```java
public boolean decrementQuantity() {
    // ✅ Check if decrement actually happened by comparing old and new values
    int oldValue = quantity.getAndUpdate(current ->
        current > 0 ? current - 1 : current
    );
    return oldValue > 0;  // ✅ True only if we actually decremented
}

// OR more explicit with CAS loop:
public boolean decrementQuantity() {
    while (true) {
        int current = quantity.get();
        if (current <= 0) {
            return false;  // ✅ Out of stock
        }
        if (quantity.compareAndSet(current, current - 1)) {
            return true;  // ✅ Successfully decremented
        }
        // CAS failed, retry
    }
}
```

---

### 🔴 ISSUE #2: Singleton State - Only One Customer Can Use Machine (CRITICAL)

**Location**: `service/VendingMachineService.java:14-15`

**Problem**: Service holds global state, can't handle multiple concurrent transactions

```java
private Product selectedProduct;  // ❌ Shared across ALL customers
private double currentPayment = 0.0;  // ❌ Shared across ALL customers
```

**Impact**: Multiple customers interfere with each other

**Failure Scenario**:
```java
// Customer A selects Coke ($1.50)
service.selectProduct("COKE");
service.insertMoney(Money.ONE_DOLLAR);

// Customer B selects Water ($1.00) - OVERWRITES Customer A!
service.selectProduct("WATER");

// Customer A tries to dispense
service.dispense();
// ❌ Gets Water instead of Coke!
// ❌ Customer A paid $1.00 for Coke ($1.50) but system thinks payment is for Water ($1.00)
```

**Fix: Session-Based State Management**
```java
public class VendingMachineSession {
    private final String sessionId;
    private Product selectedProduct;
    private double currentPayment;
    private final Instant createdAt;

    public VendingMachineSession(String sessionId) {
        this.sessionId = sessionId;
        this.currentPayment = 0.0;
        this.createdAt = Instant.now();
    }

    // Session-specific methods
}

@Service
public class VendingMachineService {
    private final ConcurrentHashMap<String, Product> inventory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VendingMachineSession> sessions = new ConcurrentHashMap<>();

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new VendingMachineSession(sessionId));
        return sessionId;
    }

    public void selectProduct(String sessionId, String productId) throws SessionNotFoundException, ProductNotFoundException {
        VendingMachineSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }

        Product product = inventory.get(productId);
        if (product == null) {
            throw new ProductNotFoundException(productId);
        }

        if (!product.isAvailable()) {
            throw new OutOfStockException(productId);
        }

        synchronized (session) {
            session.setSelectedProduct(product);
            session.setCurrentPayment(0.0);
        }
    }

    public void insertMoney(String sessionId, Money money) throws SessionNotFoundException {
        VendingMachineSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }

        synchronized (session) {
            session.addPayment(money.getValue());
        }
    }

    public Map<String, Object> dispense(String sessionId)
            throws SessionNotFoundException, NoProductSelectedException, InsufficientPaymentException {
        VendingMachineSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }

        synchronized (session) {
            Product product = session.getSelectedProduct();
            if (product == null) {
                throw new NoProductSelectedException();
            }

            double payment = session.getCurrentPayment();
            if (payment < product.getPrice()) {
                throw new InsufficientPaymentException(payment, product.getPrice());
            }

            // ✅ Atomic decrement with validation
            if (!product.decrementQuantity()) {
                throw new OutOfStockException(product.getId());
            }

            double change = payment - product.getPrice();

            Map<String, Object> result = new HashMap<>();
            result.put("product", product.getName());
            result.put("change", change);

            // Clean up session
            sessions.remove(sessionId);

            return result;
        }
    }

    // ✅ Scheduled cleanup of expired sessions
    @Scheduled(fixedRate = 60000)  // Every minute
    public void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> {
            Duration age = Duration.between(entry.getValue().getCreatedAt(), now);
            return age.compareTo(Duration.ofMinutes(5)) > 0;  // 5-minute timeout
        });
    }
}
```

---

### 🔴 ISSUE #3: Floating-Point Precision Issues with Money (CRITICAL)

**Location**: `service/VendingMachineService.java:15, 44, 57`

**Problem**: Using `double` for money calculations causes precision errors

```java
private double currentPayment = 0.0;  // ❌ Floating-point

public void insertMoney(Money money) {
    currentPayment += money.getValue();  // ❌ Precision loss
}

double change = currentPayment - selectedProduct.getPrice();  // ❌ Precision errors
```

**Precision Error Example**:
```java
// Price: $0.10
// Customer inserts: 3 pennies ($0.01 each)
currentPayment = 0.01 + 0.01 + 0.01;  // ❌ May not equal exactly 0.03
// Due to floating-point representation:
// 0.01 + 0.01 + 0.01 = 0.030000000000000002

if (currentPayment >= product.getPrice()) {  // 0.030000000000000002 >= 0.10?
    // May fail unexpectedly
}
```

**Fix: Use Cents (Integer) or BigDecimal**
```java
// Option 1: Store as cents (recommended for vending machines)
public class Product {
    private final int priceInCents;  // ✅ Store as cents (150 = $1.50)

    public int getPrice() { return priceInCents; }
    public double getPriceInDollars() { return priceInCents / 100.0; }
}

public enum Money {
    PENNY(1),
    NICKEL(5),
    DIME(10),
    QUARTER(25),
    ONE_DOLLAR(100),
    FIVE_DOLLAR(500);

    private final int valueInCents;  // ✅ Cents

    public int getValue() { return valueInCents; }
}

@Service
public class VendingMachineService {
    private int currentPaymentInCents = 0;  // ✅ Integer cents

    public void insertMoney(Money money) {
        stateLock.lock();
        try {
            currentPaymentInCents += money.getValue();  // ✅ Integer addition
        } finally {
            stateLock.unlock();
        }
    }

    public Map<String, Object> dispense() {
        // ...
        int changeInCents = currentPaymentInCents - selectedProduct.getPrice();
        double changeDollars = changeInCents / 100.0;
        // ...
    }
}

// Option 2: Use BigDecimal (more flexible but slower)
public class Product {
    private final BigDecimal price;

    public BigDecimal getPrice() { return price; }
}

private BigDecimal currentPayment = BigDecimal.ZERO;

public void insertMoney(Money money) {
    currentPayment = currentPayment.add(new BigDecimal(money.getValue()));
}
```

---

### 🔴 ISSUE #4: No Change-Making Logic - Just Returns Amount (CRITICAL)

**Location**: `service/VendingMachineService.java:57, 61`

**Problem**: Returns change amount but not which coins/bills

```java
double change = currentPayment - selectedProduct.getPrice();
result.put("change", change);  // ❌ Just a number, no coins specified
```

**Impact**: Real vending machine needs to dispense actual coins

**Fix: Calculate Change Coins**
```java
public class ChangeCalculator {
    private static final Money[] DENOMINATIONS = {
        Money.TWENTY_DOLLAR, Money.TEN_DOLLAR, Money.FIVE_DOLLAR,
        Money.ONE_DOLLAR, Money.QUARTER, Money.DIME, Money.NICKEL, Money.PENNY
    };

    public static Map<Money, Integer> calculateChange(int changeInCents) {
        Map<Money, Integer> change = new LinkedHashMap<>();

        int remaining = changeInCents;
        for (Money denomination : DENOMINATIONS) {
            int count = remaining / denomination.getValue();
            if (count > 0) {
                change.put(denomination, count);
                remaining -= count * denomination.getValue();
            }
        }

        return change;
    }
}

// In VendingMachineService:
public Map<String, Object> dispense() {
    stateLock.lock();
    try {
        // ...
        int changeInCents = currentPaymentInCents - selectedProduct.getPrice();
        Map<Money, Integer> changeCoins = ChangeCalculator.calculateChange(changeInCents);

        Map<String, Object> result = new HashMap<>();
        result.put("product", selectedProduct.getName());
        result.put("changeAmount", changeInCents / 100.0);
        result.put("changeCoins", changeCoins);  // ✅ Actual coins to dispense

        reset();
        return result;
    } finally {
        stateLock.unlock();
    }
}
```

---

### 🟡 ISSUE #5: Check-Then-Act Race Condition in dispense() (MEDIUM Priority)

**Location**: `service/VendingMachineService.java:33, 56`

**Problem**: Checks availability then later decrements, not atomic

```java
// In selectProduct:
if (!product.isAvailable()) throw new Exception("Product out of stock");  // ❌ Check

// Later in dispense:
selectedProduct.decrementQuantity();  // ❌ Act (separate operation)
```

**Race Condition**:
```java
// Product has 1 item left
// Customer A: selectProduct("COKE") - passes availability check ✅
// Customer B: selectProduct("COKE") - passes availability check ✅
// Customer A: dispense() - decrements to 0 ✅
// Customer B: dispense() - decrements to -1 ❌ (or fails with fixed decrementQuantity)
```

**Fix**: Check availability atomically during dispense, as shown in Issue #1 fix

---

### 🟢 ISSUE #6: No Coin Inventory Tracking (LOW Priority - Feature Gap)

**Location**: System-wide

**Problem**: Machine doesn't track available coins for change

```java
// Real vending machine:
// - Has limited coins (e.g., 10 quarters, 20 dimes)
// - Cannot give change if coins run out
// - Needs restocking
```

**Fix: Add Coin Inventory**
```java
@Service
public class VendingMachineService {
    private final ConcurrentHashMap<Money, AtomicInteger> coinInventory = new ConcurrentHashMap<>();

    public VendingMachineService() {
        // Initialize coin inventory
        coinInventory.put(Money.QUARTER, new AtomicInteger(40));  // 40 quarters
        coinInventory.put(Money.DIME, new AtomicInteger(50));
        coinInventory.put(Money.NICKEL, new AtomicInteger(50));
        coinInventory.put(Money.PENNY, new AtomicInteger(100));
        // ...
    }

    public void insertMoney(Money money) {
        // ✅ Add inserted coin to inventory
        coinInventory.get(money).incrementAndGet();
        // ...
    }

    private boolean canMakeChange(int changeInCents) {
        // ✅ Check if we have enough coins to make change
        Map<Money, Integer> requiredCoins = ChangeCalculator.calculateChange(changeInCents);

        for (Map.Entry<Money, Integer> entry : requiredCoins.entrySet()) {
            if (coinInventory.get(entry.getKey()).get() < entry.getValue()) {
                return false;  // Not enough coins
            }
        }

        return true;
    }

    public Map<String, Object> dispense() {
        stateLock.lock();
        try {
            // ...
            int changeInCents = currentPaymentInCents - selectedProduct.getPrice();

            if (!canMakeChange(changeInCents)) {
                throw new InsufficientChangeException(
                    "Cannot make change. Please insert exact amount.");
            }

            // Dispense change and update coin inventory
            Map<Money, Integer> changeCoins = ChangeCalculator.calculateChange(changeInCents);
            for (Map.Entry<Money, Integer> entry : changeCoins.entrySet()) {
                coinInventory.get(entry.getKey()).addAndGet(-entry.getValue());
            }

            // ...
        } finally {
            stateLock.unlock();
        }
    }
}
```

---

## Additional Recommendations

### 1. Add Transaction History
```java
public class Transaction {
    private final String transactionId;
    private final String productId;
    private final int amountPaid;
    private final int changeGiven;
    private final Instant timestamp;
    private final TransactionStatus status;

    // Constructor, getters...
}

private final ConcurrentHashMap<String, Transaction> transactions = new ConcurrentHashMap<>();

public Map<String, Object> dispense(String sessionId) {
    // ...
    Transaction transaction = new Transaction(
        UUID.randomUUID().toString(),
        product.getId(),
        currentPaymentInCents,
        changeInCents,
        Instant.now(),
        TransactionStatus.SUCCESS
    );
    transactions.put(transaction.getTransactionId(), transaction);
    // ...
}
```

### 2. Add Maintenance Mode
```java
public enum MachineState {
    OPERATIONAL,
    MAINTENANCE,
    OUT_OF_SERVICE
}

private volatile MachineState machineState = MachineState.OPERATIONAL;

public void selectProduct(String sessionId, String productId) {
    if (machineState != MachineState.OPERATIONAL) {
        throw new MachineNotAvailableException("Machine is in " + machineState + " mode");
    }
    // ...
}

public void enterMaintenanceMode(String adminKey) {
    // Validate admin key
    machineState = MachineState.MAINTENANCE;
}
```

### 3. Add Temperature Control (for refrigerated items)
```java
public class RefrigeratedProduct extends Product {
    private final double minTemperature;
    private final double maxTemperature;
    private volatile double currentTemperature;

    public boolean isTemperatureOk() {
        return currentTemperature >= minTemperature && currentTemperature <= maxTemperature;
    }
}

public void dispense(String sessionId) {
    // ...
    if (product instanceof RefrigeratedProduct refProduct) {
        if (!refProduct.isTemperatureOk()) {
            throw new UnsafeTemperatureException("Product temperature out of range");
        }
    }
    // ...
}
```

---

## Testing Recommendations

### Test #1: Detect decrementQuantity Bug
```java
@Test
void testDecrementQuantityWhenOutOfStock() {
    Product product = new Product("TEST", "Test Product", 1.00, 1);

    // First decrement succeeds
    assertTrue(product.decrementQuantity());
    assertEquals(0, product.getQuantity());

    // ❌ Current implementation: second decrement returns true
    // ✅ After fix: should return false
    assertFalse(product.decrementQuantity(),
               "Should not allow decrement when quantity is 0");
    assertEquals(0, product.getQuantity());
}
```

### Test #2: Concurrent Session Isolation
```java
@Test
void testConcurrentSessionIsolation() {
    VendingMachineService service = new VendingMachineService();

    String sessionA = service.createSession();
    String sessionB = service.createSession();

    // Customer A selects Coke
    service.selectProduct(sessionA, "COKE");
    service.insertMoney(sessionA, Money.ONE_DOLLAR);
    service.insertMoney(sessionA, Money.QUARTER);
    service.insertMoney(sessionA, Money.QUARTER);

    // Customer B selects Water
    service.selectProduct(sessionB, "WATER");
    service.insertMoney(sessionB, Money.ONE_DOLLAR);

    // ✅ Customer A should get Coke
    Map<String, Object> resultA = service.dispense(sessionA);
    assertEquals("Coca Cola", resultA.get("product"));

    // ✅ Customer B should get Water
    Map<String, Object> resultB = service.dispense(sessionB);
    assertEquals("Water", resultB.get("product"));
}
```

### Test #3: Floating-Point Precision
```java
@Test
void testMoneyPrecision() {
    // Test adding small amounts doesn't cause precision errors
    VendingMachineService service = new VendingMachineService();
    String sessionId = service.createSession();

    service.selectProduct(sessionId, "WATER");  // $1.00

    // Insert 100 pennies
    for (int i = 0; i < 100; i++) {
        service.insertMoney(sessionId, Money.PENNY);
    }

    // Should be able to dispense (100 cents = $1.00)
    Map<String, Object> result = service.dispense(sessionId);
    assertEquals("Water", result.get("product"));
    assertEquals(0.0, result.get("changeAmount"));
}
```

---

## Conclusion

The Vending Machine system has **CRITICAL bugs** in quantity checking, uses singleton state that prevents concurrent usage, and suffers from floating-point precision issues. Must be completely refactored before production use.

### Severity Assessment
🔴 **CRITICAL**: Incorrect decrementQuantity() always returns true
🔴 **CRITICAL**: Singleton state prevents concurrent usage
🔴 **CRITICAL**: Floating-point precision issues with money
🔴 **CRITICAL**: No change-making logic (just returns amount)
🟡 **MEDIUM**: Check-then-act race condition
🟢 **LOW**: Missing features (coin inventory, transaction history, maintenance mode)

### Action Items
1. ❌ **DO NOT DEPLOY** - Multiple critical bugs
2. ✅ **IMMEDIATELY** fix decrementQuantity() logic
3. ✅ **IMMEDIATELY** implement session-based state management
4. ✅ **IMMEDIATELY** use integer cents instead of double
5. ✅ **IMMEDIATELY** implement proper change-making algorithm
6. ⚠️ **Add coin inventory tracking** for realistic change management
7. ⚠️ **Add transaction history** for auditing
8. ⚠️ **Add comprehensive concurrency tests**

**Verdict**: REJECTED for production. Critical bugs and architectural flaws must be fixed.

### Recommended Timeline
**Week 1**: Fix all critical bugs (decrementQuantity, sessions, cents, change-making)
**Week 2**: Add comprehensive tests, coin inventory tracking
**Week 3**: Add transaction history, maintenance mode
**Week 4**: Add monitoring, alerts for low stock/coins

**DO NOT deploy until all Week 1-2 items are complete and tested.**
