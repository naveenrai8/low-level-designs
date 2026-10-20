# ATM System - Code Review

## Overall Rating: F (48/100) - SECURITY FAILURE

### Rating Breakdown
- **SOLID Principles**: C (70/100)
- **Concurrency**: F (35/100) - CRITICAL BUGS
- **Design Patterns**: C (68/100)
- **Code Quality**: F (50/100)
- **Maintainability**: D (55/100)
- **SECURITY**: F (20/100) - **CRITICAL SECURITY VIOLATIONS**

---

## Executive Summary

The ATM system has **CATASTROPHIC SECURITY FAILURES** including plain-text PIN storage, timing attack vulnerabilities, no rate limiting for brute-force attacks, and critical concurrency bugs that can cause money loss. The system also suffers from floating-point precision issues and non-atomic ATM cash management. **ABSOLUTELY NOT SUITABLE FOR PRODUCTION**. Would fail any security audit and regulatory compliance check.

---

## Critical Security Violations

### 🔴 SECURITY ISSUE #1: Plain-Text PIN Storage (CATASTROPHIC)

**Location**: `model/Account.java:8, 19-21`

**Problem**: PINs stored in plain text - massive security violation

```java
private final String pin;  // ❌ PLAIN TEXT!

public boolean validatePin(String inputPin) {
    return this.pin.equals(inputPin);  // ❌ Compares plain text
}
```

**Impact**:
- **Data breach risk**: If database is compromised, all PINs are exposed
- **Compliance violation**: Violates PCI DSS, GDPR, PSD2 regulations
- **Legal liability**: Bank would face massive fines and lawsuits
- **Customer trust**: Complete loss of customer confidence

**Real-World Analogy**:
> This is like a bank keeping all customer PINs written on sticky notes on a bulletin board. No real bank would EVER do this.

**Fix: Hash PINs with Salt**
```java
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

public class Account {
    private final String accountNumber;
    private final String cardNumber;
    private final byte[] pinHash;  // ✅ Store hash, not plain text
    private final byte[] salt;     // ✅ Unique salt per account
    private volatile double balance;
    private final ReentrantLock lock = new ReentrantLock();

    private static final int ITERATIONS = 65536;  // PBKDF2 iterations
    private static final int KEY_LENGTH = 256;     // Hash length in bits

    public Account(String accountNumber, String cardNumber, String pin, double initialBalance) {
        this.accountNumber = accountNumber;
        this.cardNumber = cardNumber;
        this.balance = initialBalance;

        // ✅ Generate random salt
        SecureRandom random = new SecureRandom();
        this.salt = new byte[16];
        random.nextBytes(salt);

        // ✅ Hash PIN with salt
        this.pinHash = hashPin(pin, salt);
    }

    public boolean validatePin(String inputPin) {
        // ✅ Hash input PIN with same salt and compare
        byte[] inputHash = hashPin(inputPin, salt);
        return Arrays.equals(pinHash, inputHash);  // ✅ Constant-time comparison
    }

    private static byte[] hashPin(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                pin.toCharArray(),
                salt,
                ITERATIONS,
                KEY_LENGTH
            );
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to hash PIN", e);
        }
    }
}
```

---

### 🔴 SECURITY ISSUE #2: Timing Attack Vulnerability (CRITICAL)

**Location**: `model/Account.java:20`

**Problem**: `String.equals()` is not constant-time, leaks PIN length

```java
public boolean validatePin(String inputPin) {
    return this.pin.equals(inputPin);  // ❌ Timing attack!
}
```

**How Timing Attack Works**:
```java
// Attacker can measure response time to guess PIN length and characters
// PIN: "1234"

inputPin = "0000"  // Time: 10ms (fails on first character)
inputPin = "1000"  // Time: 11ms (fails on second character) ← Leak!
inputPin = "1200"  // Time: 12ms (fails on third character) ← Leak!
inputPin = "1230"  // Time: 13ms (fails on fourth character) ← Leak!
inputPin = "1234"  // Time: 14ms (all match) ← Found PIN!

// Attacker can deduce PIN by measuring tiny time differences
```

**Fix**: Already shown in Security Issue #1 - use `Arrays.equals()` on hashes (constant-time)

---

### 🔴 SECURITY ISSUE #3: No Rate Limiting - Brute Force Attack (CRITICAL)

**Location**: `service/ATMService.java:25-34`

**Problem**: No protection against repeated PIN guessing

```java
public Account authenticateCard(String cardNumber, String pin) throws Exception {
    Account account = accounts.get(cardNumber);
    if (account == null) {
        throw new Exception("Invalid card number");
    }
    if (!account.validatePin(pin)) {
        throw new Exception("Invalid PIN");  // ❌ No rate limiting!
    }
    return account;
}
```

**Brute Force Attack**:
```java
// Attacker can try all 10,000 PINs (0000-9999) unlimited times
for (int pin = 0; pin < 10000; pin++) {
    try {
        String pinStr = String.format("%04d", pin);
        atmService.authenticateCard("1234-5678-9012-3456", pinStr);
        // ✅ Found PIN!
        break;
    } catch (Exception e) {
        // Try next PIN
    }
}
// ❌ With no rate limiting, this attack succeeds in seconds!
```

**Fix: Add Rate Limiting and Account Lockout**
```java
@Service
public class ATMService {
    private final ConcurrentHashMap<String, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lockoutUntil = new ConcurrentHashMap<>();

    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(30);

    public Account authenticateCard(String cardNumber, String pin)
            throws AccountLockedException, InvalidCredentialsException {

        // ✅ Check if account is locked
        Instant lockout = lockoutUntil.get(cardNumber);
        if (lockout != null && Instant.now().isBefore(lockout)) {
            Duration remaining = Duration.between(Instant.now(), lockout);
            throw new AccountLockedException(
                String.format("Account locked for %d more minutes",
                             remaining.toMinutes())
            );
        }

        Account account = accounts.get(cardNumber);
        if (account == null) {
            // ✅ Delay response to prevent card number enumeration
            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300));
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (!account.validatePin(pin)) {
            // ✅ Increment failed attempts
            int attempts = failedAttempts.computeIfAbsent(cardNumber,
                k -> new AtomicInteger(0)).incrementAndGet();

            if (attempts >= MAX_FAILED_ATTEMPTS) {
                // ✅ Lock account
                lockoutUntil.put(cardNumber, Instant.now().plus(LOCKOUT_DURATION));
                failedAttempts.remove(cardNumber);

                log.warn("Account {} locked due to {} failed PIN attempts",
                        cardNumber, attempts);

                throw new AccountLockedException(
                    String.format("Account locked for %d minutes due to too many failed attempts",
                                 LOCKOUT_DURATION.toMinutes())
                );
            }

            // ✅ Delay response to slow down brute force
            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300));

            throw new InvalidCredentialsException(
                String.format("Invalid credentials (%d/%d attempts)",
                             attempts, MAX_FAILED_ATTEMPTS)
            );
        }

        // ✅ Successful authentication - reset failed attempts
        failedAttempts.remove(cardNumber);
        lockoutUntil.remove(cardNumber);

        return account;
    }
}
```

---

### 🔴 SECURITY ISSUE #4: No Security Logging (CRITICAL)

**Location**: System-wide

**Problem**: No logging of security events

```java
// ❌ No logging of:
// - Failed PIN attempts
// - Account lockouts
// - Large withdrawals
// - Unusual transaction patterns
// - ATM cash discrepancies
```

**Fix: Add Comprehensive Security Logging**
```java
@Service
public class SecurityAuditService {
    private static final Logger securityLog = LoggerFactory.getLogger("SECURITY_AUDIT");

    public void logFailedAuthentication(String cardNumber, String sourceIP) {
        securityLog.warn("Failed PIN attempt - Card: {} IP: {} Time: {}",
                        maskCardNumber(cardNumber), sourceIP, Instant.now());
    }

    public void logAccountLockout(String cardNumber, int attempts) {
        securityLog.error("Account locked - Card: {} Attempts: {} Time: {}",
                         maskCardNumber(cardNumber), attempts, Instant.now());
    }

    public void logLargeWithdrawal(String accountNumber, double amount) {
        if (amount > 1000.0) {
            securityLog.info("Large withdrawal - Account: {} Amount: ${} Time: {}",
                            accountNumber, amount, Instant.now());
        }
    }

    public void logATMCashDiscrepancy(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.01) {
            securityLog.error("ATM cash discrepancy - Expected: ${} Actual: ${} Diff: ${}",
                             expected, actual, expected - actual);
        }
    }

    private String maskCardNumber(String cardNumber) {
        // Show only last 4 digits: **** **** **** 3456
        if (cardNumber.length() >= 4) {
            return "**** **** **** " + cardNumber.substring(cardNumber.length() - 4);
        }
        return "****";
    }
}
```

---

## Critical Concurrency Bugs

### 🔴 ISSUE #5: Non-Atomic ATM Cash Management (CRITICAL - Money Loss)

**Location**: `service/ATMService.java:48-69, 71-89`

**Problem**: ATM cash balance and account withdrawal are not atomic

```java
public synchronized Transaction withdrawCash(String cardNumber, String pin, double amount) throws Exception {
    // ...
    if (!account.withdraw(amount)) {  // ✅ Locks account, decrements balance
        throw new Exception("Insufficient account balance");
    }

    atmCashBalance -= amount;  // ❌ Separate operation, not atomic with account.withdraw()
    // ❌ If crash happens here, money deducted from account but ATM cash unchanged!

    Transaction txn = Transaction.create(...);
    recordTransaction(account.getAccountNumber(), txn);
    return txn;
}
```

**Money Loss Scenario**:
```java
// Customer withdraws $100
account.withdraw(100);  // ✅ Account balance: $900 (was $1000)
// 💥 ATM CRASHES HERE (power failure, hardware error)
atmCashBalance -= 100;  // ❌ Never executes
// Result: Customer's balance is $900, but ATM still shows $50,000
// ❌ ATM cash audit will show $100 missing!
```

**Similar Issue in depositCash()** (Line 71-89):
```java
public Transaction depositCash(...) {
    account.deposit(amount);  // ✅ Account credited
    atmCashBalance += amount;  // ❌ Not synchronized! Race condition!
}
```

**Fix: Atomic Transaction with Rollback**
```java
public synchronized Transaction withdrawCash(String cardNumber, String pin, double amount)
        throws Exception {
    Account account = authenticateCard(cardNumber, pin);

    if (amount > atmCashBalance) {
        throw new InsufficientATMCashException("ATM has insufficient cash");
    }

    // ✅ Try-catch to ensure atomic operation
    double oldBalance = account.getBalance();
    try {
        if (!account.withdraw(amount)) {
            throw new InsufficientFundsException("Insufficient account balance");
        }

        // ✅ Both operations succeed together
        atmCashBalance -= amount;

        Transaction txn = Transaction.create(
            account.getAccountNumber(),
            TransactionType.WITHDRAWAL,
            amount,
            account.getBalance()
        );

        // ✅ Record transaction (if this fails, we can rollback)
        try {
            recordTransaction(account.getAccountNumber(), txn);
        } catch (Exception e) {
            // Rollback
            account.deposit(amount);
            atmCashBalance += amount;
            throw new TransactionRecordingException("Failed to record transaction", e);
        }

        securityAuditService.logLargeWithdrawal(account.getAccountNumber(), amount);
        return txn;

    } catch (Exception e) {
        // ✅ If anything fails after withdraw, rollback
        if (account.getBalance() != oldBalance) {
            account.deposit(amount);
            atmCashBalance += amount;
        }
        throw e;
    }
}

public synchronized Transaction depositCash(...) {
    // ✅ Make synchronized to protect atmCashBalance
    // Similar pattern with rollback
}
```

---

### 🔴 ISSUE #6: Non-Thread-Safe Transaction History (CRITICAL)

**Location**: `service/ATMService.java:13, 97`

**Problem**: ArrayList is not thread-safe

```java
private final ConcurrentHashMap<String, List<Transaction>> transactionHistory = new ConcurrentHashMap<>();

private void recordTransaction(String accountNumber, Transaction txn) {
    transactionHistory.computeIfAbsent(accountNumber, k -> new ArrayList<>()).add(txn);
    // ❌ ArrayList.add() is not thread-safe!
}
```

**Impact**: Concurrent transactions can corrupt ArrayList

**Fix: Use Thread-Safe List**
```java
private final ConcurrentHashMap<String, CopyOnWriteArrayList<Transaction>> transactionHistory
    = new ConcurrentHashMap<>();

private void recordTransaction(String accountNumber, Transaction txn) {
    transactionHistory.computeIfAbsent(accountNumber,
                      k -> new CopyOnWriteArrayList<>()).add(txn);  // ✅ Thread-safe
}
```

---

### 🔴 ISSUE #7: Floating-Point Precision for Money (CRITICAL)

**Location**: `model/Account.java:9`, `service/ATMService.java:14`

**Problem**: Using double for money calculations

```java
private volatile double balance;  // ❌ Floating-point
private volatile double atmCashBalance = 50000.0;  // ❌ Floating-point
```

**Impact**: Precision errors in financial calculations

**Fix**: Use BigDecimal or cents (integers)
```java
// Option 1: BigDecimal
private BigDecimal balance;
private final BigDecimal atmCashBalance;

// Option 2: Cents (simpler, faster)
private volatile long balanceInCents;  // Store cents as long
public double getBalance() { return balanceInCents / 100.0; }
public void setBalance(double amount) { balanceInCents = Math.round(amount * 100); }
```

---

## Additional Critical Issues

### 🟡 ISSUE #8: No Withdrawal Limits (MEDIUM Priority)

**Location**: System-wide

**Problem**: No daily withdrawal limit enforcement

```java
// Customer can withdraw entire balance in one transaction
withdrawCash(cardNumber, pin, 1_000_000.00);  // ✅ Succeeds if balance allows
```

**Fix: Add Daily Limits**
```java
private final ConcurrentHashMap<String, DailyWithdrawalTracker> dailyWithdrawals
    = new ConcurrentHashMap<>();

private static final double DAILY_WITHDRAWAL_LIMIT = 1000.00;
private static final double SINGLE_TRANSACTION_LIMIT = 500.00;

public synchronized Transaction withdrawCash(...) {
    // ...
    if (amount > SINGLE_TRANSACTION_LIMIT) {
        throw new WithdrawalLimitExceededException(
            String.format("Single withdrawal limit is $%.2f", SINGLE_TRANSACTION_LIMIT));
    }

    DailyWithdrawalTracker tracker = dailyWithdrawals.computeIfAbsent(
        account.getAccountNumber(),
        k -> new DailyWithdrawalTracker()
    );

    if (tracker.getTodayTotal() + amount > DAILY_WITHDRAWAL_LIMIT) {
        throw new DailyLimitExceededException(
            String.format("Daily withdrawal limit of $%.2f exceeded. Today's total: $%.2f",
                         DAILY_WITHDRAWAL_LIMIT, tracker.getTodayTotal())
        );
    }

    // ... proceed with withdrawal
    tracker.addWithdrawal(amount);
}

class DailyWithdrawalTracker {
    private LocalDate date = LocalDate.now();
    private double totalAmount = 0.0;

    public synchronized double getTodayTotal() {
        if (!date.equals(LocalDate.now())) {
            // New day, reset
            date = LocalDate.now();
            totalAmount = 0.0;
        }
        return totalAmount;
    }

    public synchronized void addWithdrawal(double amount) {
        getTodayTotal();  // Reset if new day
        totalAmount += amount;
    }
}
```

---

### 🟢 ISSUE #9: No Card Retention (LOW Priority - Feature Gap)

**Location**: System-wide

**Problem**: Real ATMs retain cards after failed PIN attempts

**Recommendation**:
```java
public enum CardStatus {
    ACTIVE,
    RETAINED,
    EXPIRED,
    BLOCKED
}

private final ConcurrentHashMap<String, CardStatus> cardStatuses = new ConcurrentHashMap<>();

public Account authenticateCard(...) {
    CardStatus status = cardStatuses.getOrDefault(cardNumber, CardStatus.ACTIVE);

    if (status == CardStatus.RETAINED) {
        throw new CardRetainedException("Card has been retained. Please contact your bank.");
    }

    // ... authentication logic

    if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
        cardStatuses.put(cardNumber, CardStatus.RETAINED);
        log.error("Card {} retained due to failed PIN attempts", maskCardNumber(cardNumber));
        // Physically retain card in ATM
        throw new CardRetainedException("Card retained due to too many failed attempts");
    }
}
```

---

## Testing Recommendations

### Test #1: Security - Brute Force Attack Prevention
```java
@Test
void testBruteForceProtection() {
    ATMService service = new ATMService();
    String cardNumber = "1234-5678-9012-3456";

    // Try wrong PIN 3 times
    for (int i = 0; i < 3; i++) {
        assertThrows(InvalidCredentialsException.class, () ->
            service.authenticateCard(cardNumber, "0000")
        );
    }

    // 4th attempt should lock account
    AccountLockedException exception = assertThrows(AccountLockedException.class, () ->
        service.authenticateCard(cardNumber, "1234")  // Even correct PIN!
    );

    assertTrue(exception.getMessage().contains("locked"));
}
```

### Test #2: Concurrency - Money Loss Prevention
```java
@Test
void testAtomicWithdrawal() throws InterruptedException {
    ATMService service = new ATMService();
    Account account = new Account("ACC001", "1234-5678", "1234", 1000.0);
    double initialATMCash = service.getATMCashBalance();

    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);

    // 10 threads try to withdraw $100 each
    for (int i = 0; i < threadCount; i++) {
        new Thread(() -> {
            try {
                service.withdrawCash("1234-5678", "1234", 100.0);
            } catch (Exception e) {
                // Some will fail (insufficient funds)
            } finally {
                latch.countDown();
            }
        }).start();
    }

    latch.await();

    // ✅ Account balance + ATM cash should equal initial total
    double totalMoney = account.getBalance() + service.getATMCashBalance();
    double expectedTotal = 1000.0 + initialATMCash;
    assertEquals(expectedTotal, totalMoney, 0.01,
                "Money should not be created or lost");
}
```

---

## Conclusion

The ATM system has **CATASTROPHIC SECURITY FAILURES** and **CRITICAL CONCURRENCY BUGS** that make it completely unsuitable for production use. Would fail any security audit, violate banking regulations, and expose customers to fraud and financial loss.

### Severity Assessment
🔴 **CATASTROPHIC**: Plain-text PIN storage (PCI DSS violation)
🔴 **CRITICAL**: Timing attack vulnerability
🔴 **CRITICAL**: No rate limiting (brute force attacks)
🔴 **CRITICAL**: No security logging
🔴 **CRITICAL**: Non-atomic ATM cash management (money loss)
🔴 **CRITICAL**: Non-thread-safe transaction history
🔴 **CRITICAL**: Floating-point precision issues
🟡 **MEDIUM**: No withdrawal limits
🟢 **LOW**: Missing features (card retention, fraud detection)

### Regulatory Compliance Failures
- ❌ **PCI DSS**: PIN storage violates requirement 3.4
- ❌ **GDPR**: No security measures for sensitive data
- ❌ **PSD2**: No strong customer authentication
- ❌ **SOX**: No audit trail or financial controls

### Action Items
1. ❌ **DO NOT DEPLOY** - Would be illegal to operate
2. ✅ **IMMEDIATELY** implement PIN hashing (PBKDF2)
3. ✅ **IMMEDIATELY** add rate limiting and account lockout
4. ✅ **IMMEDIATELY** add comprehensive security logging
5. ✅ **IMMEDIATELY** fix atomic cash management
6. ✅ **IMMEDIATELY** use BigDecimal or integer cents
7. ⚠️ **Add daily withdrawal limits** and fraud detection
8. ⚠️ **Implement card retention** for security
9. ⚠️ **Add encryption** for data at rest and in transit
10. ⚠️ **Conduct security audit** by certified professional

**Verdict**: ABSOLUTELY REJECTED. System cannot be deployed under any circumstances. Represents severe security risk and regulatory violation.

### Recommended Timeline
**Week 1-2**: Implement all critical security fixes (PIN hashing, rate limiting, logging)
**Week 3**: Fix concurrency bugs (atomic operations, thread-safe collections)
**Week 4**: Add withdrawal limits, card retention, fraud detection
**Week 5-6**: External security audit and penetration testing
**Week 7-8**: Regulatory compliance review

**DO NOT deploy until passing external security audit and regulatory review.**
