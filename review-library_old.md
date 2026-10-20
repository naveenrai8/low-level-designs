# Library Management System - Code Review

## Overall Rating: D (62/100)

### Rating Breakdown
- **SOLID Principles**: C (70/100)
- **Concurrency**: D (55/100) - CRITICAL BUGS
- **Design Patterns**: C (68/100)
- **Code Quality**: C (72/100)
- **Maintainability**: C (70/100)

---

## Executive Summary

The Library Management System has a simple, straightforward implementation but suffers from **multiple critical concurrency bugs** that can cause data inconsistencies. The borrowBook and returnBook operations are not atomic, leading to situations where books and member records can become out of sync.

---

## Strengths

### 1. Clean Domain Model ✅
**Location**: `model/Book.java`, `model/Member.java`

Simple, focused domain objects with clear responsibilities:
```java
public class Book {
    private final String isbn;
    private final String title;
    private final String author;
    private volatile BookStatus status;

    public synchronized boolean borrow(String memberId) {
        if (status != BookStatus.AVAILABLE) {
            return false;
        }
        this.status = BookStatus.BORROWED;
        this.borrowedBy = memberId;
        return true;
    }
}
```

### 2. Good Use of ConcurrentHashMap ✅
**Location**: `service/LibraryService.java`

```java
private final ConcurrentHashMap<String, Book> books = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();
```

### 3. Search Functionality ✅
**Location**: `service/LibraryService.java`

```java
public List<Book> searchBooks(String keyword) {
    return books.values().stream()
            .filter(book -> book.getTitle().toLowerCase().contains(keyword.toLowerCase()) ||
                           book.getAuthor().toLowerCase().contains(keyword.toLowerCase()))
            .collect(Collectors.toList());
}
```

---

## Critical Issues

### 🔴 ISSUE #1: Non-Atomic borrowBook Operation (CRITICAL)

**Location**: `service/LibraryService.java:26-37`

**Problem**: Book borrow and member update are not atomic

```java
public boolean borrowBook(String isbn, String memberId) throws Exception {
    Book book = books.get(isbn);
    Member member = members.get(memberId);

    if (book == null) throw new Exception("Book not found");
    if (member == null) throw new Exception("Member not found");

    if (book.borrow(memberId)) {          // ❌ Locks book only
        member.addBorrowedBook(isbn);     // ❌ Locks member only
        return true;
    }
    return false;
}
```

**Root Cause**: Two separate synchronized methods are called sequentially without overall synchronization

**Impact**: Race conditions leading to inconsistent state:

**Scenario 1: Book borrowed but not recorded**
```java
// Thread 1                              // Thread 2
book.borrow("member1")  // ✅ Success
                                         book.borrow("member1")  // ❌ Fails (already borrowed)
                                         return false
member.addBorrowedBook(isbn)  // ✅
// Result: Member thinks they have book, but book says borrowed by thread 2
```

**Scenario 2: Member records book they don't have**
```java
// Thread 1                              // Thread 2
book.borrow("member1")  // ✅ Success
                                         returnBook(isbn, "member1")  // ✅ Book now available
                                         book.status = AVAILABLE
member.addBorrowedBook(isbn)  // ✅
// Result: Member thinks they have book, but book is actually available
```

**Fix Option 1: Service-Level Lock (Simplest)**
```java
private final Object borrowLock = new Object();

public boolean borrowBook(String isbn, String memberId)
        throws BookNotFoundException, MemberNotFoundException {
    synchronized (borrowLock) {
        Book book = books.get(isbn);
        Member member = members.get(memberId);

        if (book == null) throw new BookNotFoundException(isbn);
        if (member == null) throw new MemberNotFoundException(memberId);

        if (book.borrow(memberId)) {
            member.addBorrowedBook(isbn);
            return true;
        }
        return false;
    }
}

public void returnBook(String isbn, String memberId)
        throws BookNotFoundException, MemberNotFoundException {
    synchronized (borrowLock) {
        Book book = books.get(isbn);
        Member member = members.get(memberId);

        if (book == null) throw new BookNotFoundException(isbn);
        if (member == null) throw new MemberNotFoundException(memberId);

        book.returnBook();
        member.removeBorrowedBook(isbn);
    }
}
```

**Fix Option 2: Fine-Grained Locking with Ordering (Better Performance)**
```java
public boolean borrowBook(String isbn, String memberId)
        throws BookNotFoundException, MemberNotFoundException {
    Book book = books.get(isbn);
    Member member = members.get(memberId);

    if (book == null) throw new BookNotFoundException(isbn);
    if (member == null) throw new MemberNotFoundException(memberId);

    // Lock in consistent order to prevent deadlock
    // Use string comparison to ensure consistent ordering
    Object first = isbn.compareTo(memberId) < 0 ? book : member;
    Object second = first == book ? member : book;

    synchronized (first) {
        synchronized (second) {
            if (book.borrow(memberId)) {
                member.addBorrowedBook(isbn);
                return true;
            }
            return false;
        }
    }
}
```

**Fix Option 3: Event Sourcing (Production-Grade)**
```java
public class BorrowBookCommand {
    private final String isbn;
    private final String memberId;
    private final Instant timestamp;
}

public class LibraryEventStore {
    private final List<BorrowBookCommand> commands = new CopyOnWriteArrayList<>();

    public synchronized boolean executeCommand(BorrowBookCommand cmd) {
        // Validate command
        // Execute atomically
        // Store event
        // Return result
    }

    // Can replay events for audit, debugging, or recovery
}
```

---

### 🔴 ISSUE #2: Generic Exception Anti-Pattern (CRITICAL)

**Location**: `service/LibraryService.java`

**Problem**: Using generic `Exception` makes error handling impossible

```java
if (book == null) throw new Exception("Book not found");  // ❌
if (member == null) throw new Exception("Member not found");  // ❌
```

**Impact**:
- Controllers cannot distinguish between error types
- Forces catch(Exception) which catches everything
- Violates Open/Closed Principle
- Makes API documentation unclear

**Fix**: Create specific exception hierarchy
```java
// Base exception
public abstract class LibraryException extends RuntimeException {
    protected LibraryException(String message) {
        super(message);
    }
}

// Specific exceptions
public class BookNotFoundException extends LibraryException {
    public BookNotFoundException(String isbn) {
        super("Book not found: " + isbn);
    }
}

public class MemberNotFoundException extends LibraryException {
    public MemberNotFoundException(String memberId) {
        super("Member not found: " + memberId);
    }
}

public class BookNotAvailableException extends LibraryException {
    private final String isbn;
    private final String borrowedBy;

    public BookNotAvailableException(String isbn, String borrowedBy) {
        super(String.format("Book %s is not available (borrowed by %s)", isbn, borrowedBy));
        this.isbn = isbn;
        this.borrowedBy = borrowedBy;
    }

    public String getIsbn() { return isbn; }
    public String getBorrowedBy() { return borrowedBy; }
}

public class BorrowLimitExceededException extends LibraryException {
    private final String memberId;
    private final int currentBorrowCount;
    private final int maxAllowed;

    public BorrowLimitExceededException(String memberId, int current, int max) {
        super(String.format("Member %s has %d books (max %d)", memberId, current, max));
        this.memberId = memberId;
        this.currentBorrowCount = current;
        this.maxAllowed = max;
    }
}

// Usage in service
public boolean borrowBook(String isbn, String memberId) {
    Book book = books.get(isbn);
    if (book == null) {
        throw new BookNotFoundException(isbn);  // ✅ Specific exception
    }

    Member member = members.get(memberId);
    if (member == null) {
        throw new MemberNotFoundException(memberId);  // ✅ Specific exception
    }

    // Check borrow limit
    if (member.getBorrowedBooks().size() >= MAX_BOOKS_PER_MEMBER) {
        throw new BorrowLimitExceededException(
            memberId,
            member.getBorrowedBooks().size(),
            MAX_BOOKS_PER_MEMBER
        );
    }

    // ...
}
```

**Controller can now handle specifically:**
```java
@ExceptionHandler(BookNotFoundException.class)
public ResponseEntity<?> handleBookNotFound(BookNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("BOOK_NOT_FOUND", e.getMessage()));
}

@ExceptionHandler(BookNotAvailableException.class)
public ResponseEntity<?> handleBookNotAvailable(BookNotAvailableException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("BOOK_NOT_AVAILABLE", e.getMessage(),
                                   Map.of("borrowedBy", e.getBorrowedBy())));
}

@ExceptionHandler(BorrowLimitExceededException.class)
public ResponseEntity<?> handleBorrowLimitExceeded(BorrowLimitExceededException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("BORROW_LIMIT_EXCEEDED", e.getMessage(),
                                   Map.of("current", e.getCurrentBorrowCount(),
                                         "max", e.getMaxAllowed())));
}
```

---

### 🟡 ISSUE #3: Non-Thread-Safe ArrayList (MEDIUM)

**Location**: `model/Member.java:10`

**Problem**: ArrayList is not thread-safe

```java
private final List<String> borrowedBooks;

public Member(String memberId, String name, String email) {
    this.borrowedBooks = new ArrayList<>();  // ❌ Not thread-safe
}
```

**Impact**: Even with synchronized methods, ArrayList internal structure can be corrupted if:
1. Multiple threads call getBorrowedBooks() and iterate concurrently
2. Modification during iteration causes ConcurrentModificationException

**Fix Option 1: Use CopyOnWriteArrayList**
```java
private final CopyOnWriteArrayList<String> borrowedBooks = new CopyOnWriteArrayList<>();

// Pros: Thread-safe, no ConcurrentModificationException
// Cons: High cost for writes (copies entire array)
```

**Fix Option 2: Defensive Copying**
```java
private final List<String> borrowedBooks = new ArrayList<>();

public synchronized List<String> getBorrowedBooks() {
    return new ArrayList<>(borrowedBooks);  // ✅ Return copy
}

public synchronized void addBorrowedBook(String isbn) {
    borrowedBooks.add(isbn);
}

public synchronized void removeBorrowedBook(String isbn) {
    borrowedBooks.remove(isbn);
}
```

---

### 🟢 ISSUE #4: Missing Business Rules (LOW)

**Location**: `service/LibraryService.java`

**Problem**: No enforcement of common library rules

**Missing Rules**:
1. Maximum books per member
2. Loan duration limits
3. Overdue tracking
4. Reservation system
5. Fine calculation

**Recommendation**:
```java
public class LibraryPolicy {
    public static final int MAX_BOOKS_PER_MEMBER = 5;
    public static final int LOAN_DURATION_DAYS = 14;
    public static final double FINE_PER_DAY = 0.50;
}

public class Loan {
    private final String isbn;
    private final String memberId;
    private final LocalDate borrowDate;
    private final LocalDate dueDate;
    private LocalDate returnDate;

    public Loan(String isbn, String memberId) {
        this.isbn = isbn;
        this.memberId = memberId;
        this.borrowDate = LocalDate.now();
        this.dueDate = borrowDate.plusDays(LibraryPolicy.LOAN_DURATION_DAYS);
    }

    public boolean isOverdue() {
        return returnDate == null && LocalDate.now().isAfter(dueDate);
    }

    public double calculateFine() {
        if (!isOverdue()) return 0.0;
        long daysOverdue = ChronoUnit.DAYS.between(dueDate, LocalDate.now());
        return daysOverdue * LibraryPolicy.FINE_PER_DAY;
    }
}
```

---

## Additional Issues

### Issue #5: No Validation Layer

**Problem**: No validation of inputs

```java
public boolean borrowBook(String isbn, String memberId) {
    // ❌ No validation:
    // - isbn could be null or empty
    // - memberId could be null or empty
    // - No format validation
}
```

**Recommendation**:
```java
public boolean borrowBook(@NotBlank String isbn, @NotBlank String memberId) {
    Objects.requireNonNull(isbn, "ISBN cannot be null");
    Objects.requireNonNull(memberId, "Member ID cannot be null");

    if (isbn.trim().isEmpty()) {
        throw new IllegalArgumentException("ISBN cannot be empty");
    }

    if (!isValidIsbn(isbn)) {
        throw new IllegalArgumentException("Invalid ISBN format: " + isbn);
    }

    // Continue...
}

private boolean isValidIsbn(String isbn) {
    // ISBN-10: 10 digits with optional hyphens
    // ISBN-13: 13 digits with optional hyphens
    return isbn.matches("^(?:ISBN(?:-1[03])?:? )?(?=[0-9X]{10}$|(?=(?:[0-9]+[- ]){3})[- 0-9X]{13}$|97[89][0-9]{10}$|(?=(?:[0-9]+[- ]){4})[- 0-9]{17}$)(?:97[89][- ]?)?[0-9]{1,5}[- ]?[0-9]+[- ]?[0-9]+[- ]?[0-9X]$");
}
```

### Issue #6: Missing Logging

**Problem**: No observability

**Recommendation**:
```java
@Service
@Slf4j
public class LibraryService {

    public boolean borrowBook(String isbn, String memberId) {
        log.info("Borrow request - isbn: {}, memberId: {}", isbn, memberId);

        try {
            // ... operation
            log.info("Book borrowed successfully - isbn: {}, memberId: {}", isbn, memberId);
            return true;
        } catch (BookNotFoundException e) {
            log.warn("Book not found - isbn: {}", isbn);
            throw e;
        } catch (Exception e) {
            log.error("Failed to borrow book - isbn: {}, memberId: {}", isbn, memberId, e);
            throw e;
        }
    }
}
```

---

## Testing Recommendations

### Test #1: Concurrent Borrow Race Condition
```java
@Test
void testConcurrentBorrowSameBook() throws InterruptedException {
    LibraryService service = new LibraryService();

    Book book = new Book("ISBN-001", "Test Book", "Author", 2024);
    service.addBook(book);

    Member member1 = new Member("M001", "Alice", "alice@test.com");
    Member member2 = new Member("M002", "Bob", "bob@test.com");
    service.registerMember(member1);
    service.registerMember(member2);

    CountDownLatch latch = new CountDownLatch(2);
    AtomicInteger successCount = new AtomicInteger(0);

    // Two threads try to borrow same book
    Runnable borrowTask = () -> {
        try {
            if (service.borrowBook("ISBN-001", member1.getMemberId())) {
                successCount.incrementAndGet();
            }
        } catch (Exception e) {
            // Expected for one thread
        } finally {
            latch.countDown();
        }
    };

    new Thread(borrowTask).start();
    new Thread(borrowTask).start();

    latch.await();

    // ✅ Exactly one borrow should succeed
    assertEquals(1, successCount.get());

    // ✅ Book should be borrowed
    assertEquals(BookStatus.BORROWED, book.getStatus());

    // ✅ Member should have book recorded
    assertTrue(member1.getBorrowedBooks().contains("ISBN-001"));
}
```

### Test #2: Detect Non-Atomic Operation Bug
```java
@Test
void testBorrowReturnRaceCondition() throws InterruptedException {
    LibraryService service = new LibraryService();

    Book book = new Book("ISBN-001", "Test Book", "Author", 2024);
    service.addBook(book);

    Member member = new Member("M001", "Alice", "alice@test.com");
    service.registerMember(member);

    // Borrow initially
    service.borrowBook("ISBN-001", "M001");

    CyclicBarrier barrier = new CyclicBarrier(2);
    CountDownLatch latch = new CountDownLatch(2);

    // Thread 1: Return book
    Thread thread1 = new Thread(() -> {
        try {
            barrier.await();
            service.returnBook("ISBN-001", "M001");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            latch.countDown();
        }
    });

    // Thread 2: Check availability and try to borrow
    Thread thread2 = new Thread(() -> {
        try {
            barrier.await();
            Thread.sleep(5);  // Small delay to create race window
            if (book.getStatus() == BookStatus.AVAILABLE) {
                service.borrowBook("ISBN-001", "M001");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            latch.countDown();
        }
    });

    thread1.start();
    thread2.start();
    latch.await();

    // ❌ Current implementation: Book and Member can be inconsistent
    // ✅ After fix: Book status should match Member's borrowedBooks list
    boolean memberHasBook = member.getBorrowedBooks().contains("ISBN-001");
    boolean bookIsBorrowed = book.getStatus() == BookStatus.BORROWED;

    assertEquals(memberHasBook, bookIsBorrowed,
                "Book status and member records should be consistent");
}
```

---

## Architectural Recommendations

### 1. Introduce Repository Pattern
```java
public interface BookRepository {
    void save(Book book);
    Optional<Book> findByIsbn(String isbn);
    List<Book> findByTitle(String title);
    List<Book> findByAuthor(String author);
    List<Book> findAvailable();
}

public interface MemberRepository {
    void save(Member member);
    Optional<Member> findById(String memberId);
    List<Member> findAll();
}

@Service
public class LibraryService {
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;

    public LibraryService(BookRepository bookRepository,
                         MemberRepository memberRepository) {
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
    }
}
```

### 2. Add Domain Events
```java
public interface DomainEvent {
    Instant occurredAt();
}

public record BookBorrowedEvent(
    String isbn,
    String memberId,
    Instant borrowDate,
    Instant dueDate
) implements DomainEvent {
    @Override
    public Instant occurredAt() { return borrowDate; }
}

public record BookReturnedEvent(
    String isbn,
    String memberId,
    Instant returnDate,
    double fineAmount
) implements DomainEvent {
    @Override
    public Instant occurredAt() { return returnDate; }
}

@Component
public class LibraryEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publish(DomainEvent event) {
        eventPublisher.publishEvent(event);
    }
}

// Listeners for analytics, notifications, etc.
@Component
public class NotificationListener {
    @EventListener
    public void onBookBorrowed(BookBorrowedEvent event) {
        // Send email confirmation
    }

    @EventListener
    public void onBookOverdue(BookOverdueEvent event) {
        // Send overdue notification
    }
}
```

---

## Performance Recommendations

### Current Bottlenecks
1. **Service-level lock** (if implemented) serializes all borrow/return operations
2. **Linear search** for keyword search (O(n))
3. **No caching** for frequent lookups

### Optimizations

**1. Read-Write Separation**
```java
// Separate read and write operations
private final ReadWriteLock catalogLock = new ReentrantReadWriteLock();

public List<Book> searchBooks(String keyword) {
    catalogLock.readLock().lock();  // Multiple readers allowed
    try {
        // Search operation
    } finally {
        catalogLock.readLock().unlock();
    }
}

public void addBook(Book book) {
    catalogLock.writeLock().lock();  // Exclusive for writers
    try {
        // Add operation
    } finally {
        catalogLock.writeLock().unlock();
    }
}
```

**2. Add Search Index**
```java
private final ConcurrentHashMap<String, Set<String>> titleIndex = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Set<String>> authorIndex = new ConcurrentHashMap<>();

public void addBook(Book book) {
    books.put(book.getIsbn(), book);

    // Update indexes
    addToIndex(titleIndex, book.getTitle(), book.getIsbn());
    addToIndex(authorIndex, book.getAuthor(), book.getIsbn());
}

public List<Book> searchBooks(String keyword) {
    Set<String> isbns = new HashSet<>();

    // Search in indexes (much faster)
    titleIndex.forEach((title, bookIsbns) -> {
        if (title.toLowerCase().contains(keyword.toLowerCase())) {
            isbns.addAll(bookIsbns);
        }
    });

    return isbns.stream()
            .map(books::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
}
```

---

## Conclusion

The Library Management System requires **critical fixes** before production deployment. The non-atomic borrow/return operations will cause data inconsistencies under concurrent load.

### Severity Assessment
🔴 **CRITICAL**: Non-atomic borrowBook/returnBook operations
🔴 **CRITICAL**: Generic exception anti-pattern
🟡 **MEDIUM**: Non-thread-safe ArrayList in Member
🟢 **LOW**: Missing business rules and validation

### Action Items
1. ❌ **DO NOT DEPLOY** until atomic operations are fixed
2. ✅ **Implement service-level locking** for borrow/return
3. ✅ **Replace generic exceptions** with specific types
4. ✅ **Fix Member.borrowedBooks** thread-safety
5. ⚠️ **Add comprehensive testing** especially for concurrency
6. ⚠️ **Add validation layer** for inputs
7. ⚠️ **Implement logging** for observability

**Verdict**: REJECTED for production until critical concurrency bugs are fixed.

### Recommended Timeline
**Week 1**: Fix atomic operation bugs + add specific exceptions
**Week 2**: Add comprehensive tests (unit + concurrency)
**Week 3**: Add business rules (loan duration, fines, limits)
**Week 4**: Add observability (logging, metrics) + performance testing
