# Airline Management System - Code Review

## Overall Rating: F (55/100)

### Rating Breakdown
- **SOLID Principles**: C (70/100)
- **Concurrency**: F (40/100) - CRITICAL BUGS
- **Design Patterns**: C (65/100)
- **Code Quality**: C (68/100)
- **Maintainability**: C (62/100)

---

## Executive Summary

The Airline Management System has **CRITICAL concurrency bugs** that cause overbooking and seat allocation failures. The `bookSeat()` logic is fundamentally incorrect, multi-seat bookings are not atomic, and there's no cancellation logic to release seats. This system will fail under production load and should NOT be deployed.

---

## Strengths

### 1. Use of AtomicInteger for Seat Counter ✅
**Location**: `model/Flight.java:12`

```java
private final AtomicInteger bookedSeats;
```

Proper use of `AtomicInteger` for concurrent updates (though the update logic itself is broken).

### 2. Immutable Flight Properties ✅
**Location**: `model/Flight.java:7-13`

```java
private final String flightNumber;
private final String source;
private final String destination;
private final LocalDateTime departureTime;
private final int capacity;
```

Core flight properties are immutable, preventing accidental modification.

---

## Critical Issues

### 🔴 ISSUE #1: INCORRECT bookSeat() Logic - ALLOWS OVERBOOKING (CRITICAL)

**Location**: `model/Flight.java:26-29`

**Problem**: The condition check happens AFTER incrementing, allowing overbooking

```java
public boolean bookSeat() {
    return bookedSeats.updateAndGet(current ->
        current < capacity ? current + 1 : current  // ✅ Increment is correct
    ) <= capacity;  // ❌ WRONG: Checks result AFTER increment
}
```

**Root Cause**:
The `updateAndGet` returns the **new value** after incrementing. Checking if `newValue <= capacity` allows one extra booking beyond capacity.

**Overbooking Scenario**:
```java
// Flight with capacity = 100
// Current bookedSeats = 99

// Thread 1: bookSeat()
bookedSeats.updateAndGet(99 -> 99 < 100 ? 100 : 99)  // Returns 100
return 100 <= 100;  // ❌ Returns TRUE, books seat 100 ✅

// Thread 2: bookSeat() (concurrent)
bookedSeats.updateAndGet(100 -> 100 < 100 ? 101 : 100)  // Returns 100 (no increment)
return 100 <= 100;  // ❌ Returns TRUE even though at capacity!

// Even worse: If Thread 2 runs slightly after Thread 1
bookedSeats.updateAndGet(100 -> 100 < 100 ? 101 : 100)  // Returns 100
// But the check is <= capacity, so it returns true!
```

**Correct Implementation**:
```java
public boolean bookSeat() {
    int newValue = bookedSeats.updateAndGet(current ->
        current < capacity ? current + 1 : current
    );
    // ✅ Check if increment actually happened
    return newValue < capacity || newValue == bookedSeats.get();
}

// OR simpler and clearer:
public boolean bookSeat() {
    int oldValue = bookedSeats.getAndUpdate(current ->
        current < capacity ? current + 1 : current
    );
    // ✅ Check old value was below capacity (increment succeeded)
    return oldValue < capacity;
}

// OR most robust with explicit CAS:
public boolean bookSeat() {
    while (true) {
        int current = bookedSeats.get();
        if (current >= capacity) {
            return false;  // No seats available
        }
        if (bookedSeats.compareAndSet(current, current + 1)) {
            return true;  // Successfully booked
        }
        // CAS failed, retry
    }
}
```

---

### 🔴 ISSUE #2: Non-Atomic Multi-Seat Booking (CRITICAL)

**Location**: `service/AirlineService.java:35-43`

**Problem**: Multi-seat bookings are not atomic, can partially succeed

```java
if (flight.getAvailableSeats() < numberOfSeats) {  // ❌ Check
    throw new Exception("Not enough seats available");
}

for (int i = 0; i < numberOfSeats; i++) {  // ❌ Book one-by-one
    if (!flight.bookSeat()) {
        throw new Exception("Failed to book all seats");  // ❌ Partial booking!
    }
}
```

**Root Cause**:
1. Check-then-act race condition (another thread can book between check and act)
2. Loop books seats one at a time (not atomic)
3. If booking fails midway, already-booked seats are lost

**Failure Scenario**:
```java
// Flight has 2 seats available
// Thread 1: bookFlight(flightNumber, "Alice", 3)
if (flight.getAvailableSeats() < 3)  // 2 < 3, throws exception ✅

// BUT another scenario:
// Flight has 10 seats available
// Thread 1: bookFlight(flightNumber, "Alice", 5)
// Thread 2: bookFlight(flightNumber, "Bob", 5)

// Both check getAvailableSeats() = 10, both pass ✅
// Thread 1 books 5 seats: bookedSeats = 5
// Thread 2 starts booking:
//   - Seat 1: success (bookedSeats = 6)
//   - Seat 2: success (bookedSeats = 7)
//   - Seat 3: success (bookedSeats = 8)
//   - Seat 4: success (bookedSeats = 9)
//   - Seat 5: success (bookedSeats = 10)
// Both bookings succeed, but flight only has 10 seats!
// ❌ With the bug in bookSeat(), could even exceed capacity!

// Even worse: Partial failure
// Flight has 3 seats available
// Thread 1: bookFlight(flightNumber, "Alice", 5)
if (3 < 5) { } // Passes check ❌ (race condition)
// Concurrent thread books 2 seats, now only 1 left
for (int i = 0; i < 5; i++) {
    // i=0: bookSeat() succeeds (1 seat left)
    // i=1: bookSeat() fails (no seats)
    throw new Exception("Failed to book all seats");
}
// ❌ Result: 1 seat is booked but booking fails!
// Money charged but no booking record created!
```

**Fix: Atomic Multi-Seat Booking**
```java
// In Flight.java: Add atomic multi-seat booking
public boolean bookSeats(int count) {
    if (count <= 0) {
        throw new IllegalArgumentException("Count must be positive");
    }

    while (true) {
        int current = bookedSeats.get();
        if (current + count > capacity) {
            return false;  // Not enough seats
        }
        if (bookedSeats.compareAndSet(current, current + count)) {
            return true;  // Successfully booked all seats atomically
        }
        // CAS failed, retry
    }
}

// In AirlineService.java: Use atomic operation
public FlightBooking bookFlight(String flightNumber, String passengerName, int numberOfSeats)
        throws FlightNotFoundException, InsufficientSeatsException {

    if (numberOfSeats <= 0) {
        throw new IllegalArgumentException("Number of seats must be positive");
    }

    Flight flight = flights.get(flightNumber);
    if (flight == null) {
        throw new FlightNotFoundException(flightNumber);
    }

    // ✅ Atomic multi-seat booking
    if (!flight.bookSeats(numberOfSeats)) {
        throw new InsufficientSeatsException(
            String.format("Cannot book %d seats on flight %s, only %d available",
                         numberOfSeats, flightNumber, flight.getAvailableSeats())
        );
    }

    double totalCost = numberOfSeats * flight.getPrice();
    FlightBooking booking = new FlightBooking(flightNumber, passengerName, numberOfSeats, totalCost);
    bookings.put(booking.getBookingId(), booking);

    return booking;
}
```

---

### 🔴 ISSUE #3: No Cancellation Logic - Seats Never Released (CRITICAL)

**Location**: `model/FlightBooking.java:22-24`

**Problem**: Cancellation marks booking as cancelled but doesn't release seats

```java
public synchronized void cancel() {
    status = BookingStatus.CANCELLED;  // ❌ Seats never returned to flight
}
```

**Impact**: Once seats are booked, they can never be recovered even after cancellation

**Fix: Proper Cancellation with Seat Release**
```java
// In Flight.java: Add method to release seats
public void releaseSeats(int count) {
    if (count <= 0) {
        throw new IllegalArgumentException("Count must be positive");
    }
    bookedSeats.updateAndGet(current -> Math.max(0, current - count));
}

// In AirlineService.java: Add cancellation logic
public void cancelBooking(String bookingId) throws BookingNotFoundException {
    FlightBooking booking = bookings.get(bookingId);
    if (booking == null) {
        throw new BookingNotFoundException(bookingId);
    }

    // ✅ Atomic cancellation with seat release
    synchronized (booking) {
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Booking already cancelled");
        }

        booking.cancel();  // Mark as cancelled

        // Release seats back to flight
        Flight flight = flights.get(booking.getFlightNumber());
        if (flight != null) {
            flight.releaseSeats(booking.getNumberOfSeats());
        }
    }
}
```

---

### 🔴 ISSUE #4: Check-Then-Act Race Condition (CRITICAL)

**Location**: `service/AirlineService.java:35-37`

**Problem**: Availability check and booking are separate operations

```java
if (flight.getAvailableSeats() < numberOfSeats) {  // ❌ Read
    throw new Exception("Not enough seats available");
}

for (int i = 0; i < numberOfSeats; i++) {  // ❌ Write (separate operation)
    if (!flight.bookSeat()) { ... }
}
```

**Impact**: Multiple threads can pass the availability check and then overbook

**Fix**: Already addressed by using atomic `bookSeats(int count)` method above

---

### 🟡 ISSUE #5: Generic Exception Anti-Pattern (MEDIUM)

**Location**: Throughout `AirlineService.java`

```java
throw new Exception("Flight not found");  // ❌
throw new Exception("Not enough seats available");  // ❌
throw new Exception("Failed to book all seats");  // ❌
```

**Fix**: Create specific exceptions
```java
public class FlightNotFoundException extends RuntimeException {
    public FlightNotFoundException(String flightNumber) {
        super("Flight not found: " + flightNumber);
    }
}

public class InsufficientSeatsException extends RuntimeException {
    private final int requested;
    private final int available;

    public InsufficientSeatsException(String message, int requested, int available) {
        super(message);
        this.requested = requested;
        this.available = available;
    }

    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}

public class BookingFailedException extends RuntimeException {
    public BookingFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

### 🟢 ISSUE #6: No Validation for Edge Cases (LOW)

**Location**: `service/AirlineService.java:29`

**Problem**: No validation for edge cases

```java
public FlightBooking bookFlight(String flightNumber, String passengerName, int numberOfSeats)
    // ❌ What if numberOfSeats is 0 or negative?
    // ❌ What if numberOfSeats is 1000 (exceeds capacity)?
    // ❌ What if passengerName is null or empty?
```

**Fix**: Add validation
```java
public FlightBooking bookFlight(String flightNumber, String passengerName, int numberOfSeats)
        throws FlightNotFoundException, InsufficientSeatsException {

    // Validate input
    if (passengerName == null || passengerName.isBlank()) {
        throw new IllegalArgumentException("Passenger name cannot be blank");
    }

    if (numberOfSeats <= 0) {
        throw new IllegalArgumentException("Number of seats must be positive: " + numberOfSeats);
    }

    if (numberOfSeats > 10) {  // Business rule: max 10 seats per booking
        throw new IllegalArgumentException("Cannot book more than 10 seats per booking");
    }

    // Continue with booking logic...
}
```

---

## Additional Recommendations

### 1. Add Idempotency for Bookings
```java
public class FlightBooking {
    private final String idempotencyKey;  // Client-provided unique key

    // Constructor
    public FlightBooking(String flightNumber, String passengerName,
                        int numberOfSeats, double totalCost, String idempotencyKey) {
        // ...
        this.idempotencyKey = idempotencyKey;
    }
}

private final ConcurrentHashMap<String, FlightBooking> bookingsByIdempotencyKey
    = new ConcurrentHashMap<>();

public FlightBooking bookFlight(String flightNumber, String passengerName,
                               int numberOfSeats, String idempotencyKey) {
    // Check if already processed
    FlightBooking existing = bookingsByIdempotencyKey.get(idempotencyKey);
    if (existing != null) {
        return existing;  // Return same booking, don't double-book
    }

    // Process booking...
    FlightBooking booking = // create and execute
    bookingsByIdempotencyKey.put(idempotencyKey, booking);
    bookings.put(booking.getBookingId(), booking);
    return booking;
}
```

### 2. Add Flight Search by Date Range
```java
public List<Flight> searchFlightsByDateRange(String source, String destination,
                                             LocalDateTime startDate,
                                             LocalDateTime endDate) {
    return flights.values().stream()
            .filter(flight -> flight.getSource().equalsIgnoreCase(source))
            .filter(flight -> flight.getDestination().equalsIgnoreCase(destination))
            .filter(flight -> !flight.getDepartureTime().isBefore(startDate))
            .filter(flight -> !flight.getDepartureTime().isAfter(endDate))
            .filter(flight -> flight.getAvailableSeats() > 0)
            .sorted(Comparator.comparing(Flight::getDepartureTime))
            .collect(Collectors.toList());
}
```

### 3. Add Waitlist for Fully Booked Flights
```java
public class Flight {
    private final ConcurrentLinkedQueue<WaitlistEntry> waitlist = new ConcurrentLinkedQueue<>();

    public void addToWaitlist(String passengerName, int numberOfSeats) {
        waitlist.offer(new WaitlistEntry(passengerName, numberOfSeats, LocalDateTime.now()));
    }

    private void processWaitlist() {
        // Called after cancellation to offer seats to waitlist
        while (!waitlist.isEmpty() && getAvailableSeats() > 0) {
            WaitlistEntry entry = waitlist.peek();
            if (entry.getNumberOfSeats() <= getAvailableSeats()) {
                waitlist.poll();
                // Notify passenger (send email/notification)
                notificationService.notifyWaitlistPassenger(entry);
            } else {
                break;  // Cannot fulfill next waitlist entry
            }
        }
    }
}
```

### 4. Add Pricing Strategy
```java
@FunctionalInterface
public interface PricingStrategy {
    double calculatePrice(Flight flight, int numberOfSeats);
}

public class DynamicPricing implements PricingStrategy {
    @Override
    public double calculatePrice(Flight flight, int numberOfSeats) {
        double basePrice = flight.getPrice();
        double occupancyRate = (double) (flight.getCapacity() - flight.getAvailableSeats())
                              / flight.getCapacity();

        // Increase price as flight fills up
        double multiplier = 1.0;
        if (occupancyRate > 0.8) {
            multiplier = 1.5;  // 50% premium for last 20% of seats
        } else if (occupancyRate > 0.6) {
            multiplier = 1.25; // 25% premium
        }

        return basePrice * numberOfSeats * multiplier;
    }
}
```

---

## Testing Recommendations

### Test #1: Detect Overbooking Bug
```java
@Test
void testOverbookingPrevention() throws InterruptedException {
    Flight flight = new Flight("AA100", "NYC", "LAX",
                               LocalDateTime.now().plusDays(1), 100, 299.99);

    int threadCount = 150;  // Try to book 150 seats on 100-seat flight
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
        new Thread(() -> {
            try {
                if (flight.bookSeat()) {
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        }).start();
    }

    latch.await();

    // ✅ Should book exactly 100 seats, not more
    assertEquals(100, successCount.get(), "Should not overbook");
    assertEquals(0, flight.getAvailableSeats(), "All seats should be booked");
}
```

### Test #2: Detect Non-Atomic Multi-Seat Booking
```java
@Test
void testAtomicMultiSeatBooking() throws InterruptedException {
    AirlineService service = new AirlineService();
    Flight flight = new Flight("AA200", "NYC", "SFO",
                               LocalDateTime.now().plusDays(1), 10, 399.99);
    service.addFlight(flight);

    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    // 10 threads each trying to book 5 seats on 10-seat flight
    for (int i = 0; i < threadCount; i++) {
        final int threadNum = i;
        new Thread(() -> {
            try {
                service.bookFlight("AA200", "Passenger" + threadNum, 5);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();
    }

    latch.await();

    // ✅ Should allow at most 2 bookings (2 * 5 = 10 seats)
    assertEquals(2, successCount.get(), "Should allow exactly 2 bookings");
    assertEquals(8, failureCount.get(), "8 bookings should fail");
    assertEquals(0, flight.getAvailableSeats(), "All seats should be booked");
}
```

### Test #3: Test Cancellation Releases Seats
```java
@Test
void testCancellationReleasesSeats() throws Exception {
    AirlineService service = new AirlineService();
    Flight flight = new Flight("AA300", "LAX", "NYC",
                               LocalDateTime.now().plusDays(1), 100, 299.99);
    service.addFlight(flight);

    // Book 5 seats
    FlightBooking booking = service.bookFlight("AA300", "Alice", 5);
    assertEquals(95, flight.getAvailableSeats());

    // Cancel booking
    service.cancelBooking(booking.getBookingId());

    // ✅ Seats should be released
    assertEquals(100, flight.getAvailableSeats(), "Seats should be released after cancellation");
    assertEquals(BookingStatus.CANCELLED, booking.getStatus());
}
```

---

## Security Recommendations

### 1. Add Rate Limiting per Passenger
```java
private final LoadingCache<String, AtomicInteger> bookingRateLimiter = CacheBuilder.newBuilder()
    .expireAfterWrite(1, TimeUnit.HOURS)
    .build(new CacheLoader<String, AtomicInteger>() {
        @Override
        public AtomicInteger load(String key) {
            return new AtomicInteger(0);
        }
    });

public FlightBooking bookFlight(String flightNumber, String passengerName, int numberOfSeats)
        throws RateLimitExceededException {

    // Limit to 10 bookings per passenger per hour
    AtomicInteger count = bookingRateLimiter.get(passengerName);
    if (count.incrementAndGet() > 10) {
        throw new RateLimitExceededException(
            "Too many booking attempts. Please try again later.");
    }

    // Continue with booking...
}
```

### 2. Add Booking Expiry
```java
public class FlightBooking {
    private final LocalDateTime expiryTime;

    public FlightBooking(...) {
        // ...
        this.expiryTime = LocalDateTime.now().plusMinutes(15);  // 15-minute hold
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiryTime);
    }
}

// Scheduled task to release expired bookings
@Scheduled(fixedRate = 60000)  // Run every minute
public void releaseExpiredBookings() {
    bookings.values().stream()
            .filter(FlightBooking::isExpired)
            .filter(b -> b.getStatus() == BookingStatus.PENDING_PAYMENT)
            .forEach(booking -> {
                try {
                    cancelBooking(booking.getBookingId());
                } catch (Exception e) {
                    log.error("Failed to cancel expired booking: {}", booking.getBookingId(), e);
                }
            });
}
```

### 3. Add Fraud Detection
```java
public class FraudDetectionService {
    public boolean isSuspiciousBooking(String passengerName, int numberOfSeats, String flightNumber) {
        // Check for:
        // - Unusual number of seats (e.g., > 10)
        // - Rapid succession of bookings from same name/IP
        // - Booking multiple flights simultaneously
        // - Patterns matching known fraud

        return false;  // Implement actual logic
    }
}
```

---

## Conclusion

The Airline Management System has **CRITICAL bugs that cause overbooking and booking failures**. The incorrect `bookSeat()` logic, non-atomic multi-seat bookings, and missing cancellation logic make this system unsuitable for production.

### Severity Assessment
🔴 **CRITICAL**: Incorrect bookSeat() logic allows overbooking
🔴 **CRITICAL**: Non-atomic multi-seat booking causes failures
🔴 **CRITICAL**: No cancellation logic (seats never released)
🔴 **CRITICAL**: Check-then-act race condition
🟡 **MEDIUM**: Generic exceptions, no validation
🟢 **LOW**: Missing features (waitlist, dynamic pricing, fraud detection)

### Action Items
1. ❌ **DO NOT DEPLOY** - Overbooking risk is unacceptable
2. ✅ **IMMEDIATELY** fix bookSeat() logic (use CAS pattern)
3. ✅ **IMMEDIATELY** implement atomic multi-seat booking
4. ✅ **IMMEDIATELY** add cancellation logic with seat release
5. ✅ **Add comprehensive testing** for overbooking prevention
6. ⚠️ **Add idempotency** for duplicate booking prevention
7. ⚠️ **Add validation** for all inputs
8. ⚠️ **Implement booking expiry** to prevent seat hoarding

**Verdict**: REJECTED for production. Critical concurrency bugs must be fixed.

### Recommended Timeline
**Week 1**: Fix critical bugs (bookSeat logic, atomic multi-seat, cancellation)
**Week 2**: Add comprehensive tests (overbooking, atomicity, cancellation)
**Week 3**: Add validation, specific exceptions, idempotency
**Week 4**: Add booking expiry, waitlist, rate limiting
**Week 5**: Security audit and fraud detection
**Week 6**: Load testing and performance tuning

**DO NOT deploy until all Week 1-2 items are complete and tested.**
