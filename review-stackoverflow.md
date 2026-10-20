# Stack Overflow Clone - Code Review

## Overall Rating: C- (70/100)

### Rating Breakdown
- **SOLID Principles**: B (80/100)
- **Concurrency**: D (55/100) - CRITICAL BUGS
- **Design Patterns**: B (78/100)
- **Code Quality**: C+ (75/100)
- **Maintainability**: C (70/100)

---

## Executive Summary

The Stack Overflow clone demonstrates good use of atomic counters for votes but has **CRITICAL thread-safety bugs** in accept answer logic, body updates, and vote tracking. The system lacks vote deduplication, reputation changes are not linked to votes, and has mutability leaks in collections. Not production-ready without fixes.

---

## Strengths

### 1. Good Use of AtomicInteger for Votes ✅
**Location**: `model/Question.java:15, 29-30`

```java
private final AtomicInteger voteCount;

public void incrementVotes() { voteCount.incrementAndGet(); }
public void decrementVotes() { voteCount.decrementAndGet(); }
```

Thread-safe vote counting using atomic operations.

### 2. Immutable IDs and Timestamps ✅
**Location**: `model/Question.java:8, 11, 13`

```java
private final Long id;
private final Long authorId;
private final Instant createdAt;
```

Core identifying fields are immutable.

### 3. Record for Vote Model ✅
**Location**: `model/Vote.java:5-17`

```java
public record Vote(
    String id,
    Long userId,
    Long targetId,
    VoteType targetType,
    VoteValue value,
    Instant createdAt
) { }
```

Proper use of Java record for immutable vote data.

### 4. Clear Service Separation ✅
**Location**: `service/QuestionService.java`

Clean separation between model and service layers.

---

## Critical Issues

### 🔴 ISSUE #1: Non-Atomic Accept Answer Operation (CRITICAL)

**Location**: `model/Question.java:31` and `model/Answer.java:27`

**Problem**: Accept answer requires two updates that are not atomic

```java
// In Question.java
public void setAcceptedAnswer(Long answerId) {
    this.acceptedAnswerId = answerId;  // ❌ Not synchronized
}

// In Answer.java
public void markAccepted() {
    this.accepted = true;  // ❌ Not synchronized, not volatile
}
```

**Impact**: Race conditions and inconsistent state

**Race Condition Scenario**:
```java
// Thread 1: Accept answer A
question.setAcceptedAnswer(answerA.getId());  // ✅ Set in question
// Context switch here
// Thread 2: Accept answer B
question.setAcceptedAnswer(answerB.getId());  // ✅ Set in question (overwrites A)
answerB.markAccepted();  // ✅ Mark B as accepted

// Thread 1 resumes
answerA.markAccepted();  // ✅ Mark A as accepted

// ❌ Result: Question points to B, but both A and B are marked accepted!
```

**Visibility Issue**:
```java
// Answer.java line 13
private boolean accepted;  // ❌ Not volatile

// Thread 1 calls markAccepted()
answer.markAccepted();  // accepted = true

// Thread 2 checks
if (answer.isAccepted()) {  // ❌ May not see the update (stale read)
    // Never executes even though answer was accepted
}
```

**Fix: Atomic Accept Answer Operation**
```java
// In Question.java
private final AtomicReference<Long> acceptedAnswerId = new AtomicReference<>(null);

public synchronized boolean acceptAnswer(Answer answer) {
    // Validate answer belongs to this question
    if (!answer.getQuestionId().equals(this.id)) {
        throw new IllegalArgumentException("Answer doesn't belong to this question");
    }

    // Check if there's already an accepted answer
    Long currentAccepted = acceptedAnswerId.get();
    if (currentAccepted != null && !currentAccepted.equals(answer.getId())) {
        // Unmark previous accepted answer
        // (Would need reference to answer service)
        return false;  // Or throw exception
    }

    // ✅ Atomic operation: set in question AND mark in answer
    acceptedAnswerId.set(answer.getId());
    answer.markAccepted();
    return true;
}

public Long getAcceptedAnswerId() {
    return acceptedAnswerId.get();
}

// In Answer.java
private volatile boolean accepted = false;  // ✅ Now volatile

public synchronized void markAccepted() {
    this.accepted = true;
}

public synchronized void unmarkAccepted() {
    this.accepted = false;
}
```

---

### 🔴 ISSUE #2: Non-Atomic Body Update (CRITICAL)

**Location**: `model/Question.java:32-35`

**Problem**: Body update and timestamp update are not atomic

```java
public void updateBody(String newBody) {
    this.body = newBody;  // ❌ Write 1
    this.updatedAt = Instant.now();  // ❌ Write 2 (not atomic with write 1)
}
```

**Race Condition Scenario**:
```java
// Thread 1: updateBody("New body 1")
this.body = "New body 1";  // ✅
// Context switch
// Thread 2: updateBody("New body 2")
this.body = "New body 2";  // ✅ Overwrites
this.updatedAt = Instant.now();  // Time: 10:00:00

// Thread 1 resumes
this.updatedAt = Instant.now();  // Time: 10:00:05

// ❌ Result: body = "New body 2", updatedAt = 10:00:05
// Timestamp suggests body was updated at 10:00:05, but actual update was at 10:00:00
```

**Visibility Issue**:
```java
// Line 10, 14
private String body;  // ❌ Not volatile
private Instant updatedAt;  // ❌ Not volatile

// Thread 1 updates
question.updateBody("New content");

// Thread 2 reads
String content = question.getBody();  // ❌ May see stale value
Instant updated = question.getUpdatedAt();  // ❌ May see stale value
```

**Fix: Synchronized Update with Volatile Fields**
```java
public class Question {
    private volatile String body;  // ✅ Volatile for visibility
    private volatile Instant updatedAt;  // ✅ Volatile for visibility

    public synchronized void updateBody(String newBody) {
        if (newBody == null || newBody.isBlank()) {
            throw new IllegalArgumentException("Body cannot be blank");
        }
        this.body = newBody;
        this.updatedAt = Instant.now();
    }

    // Make getters synchronized or rely on volatile
    public String getBody() {
        return body;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

---

### 🔴 ISSUE #3: Tags Mutability Leak (CRITICAL)

**Location**: `model/Question.java:42`

**Problem**: Returns internal Set reference

```java
public Set<String> getTags() {
    return tags;  // ❌ Exposes mutable internal state
}
```

**Impact**: Callers can modify tags directly

**Exploit Scenario**:
```java
Question question = questionService.postQuestion(
    userId, "Title", "Body", Set.of("java", "spring")
);

// ❌ Caller can modify tags without going through service
Set<String> tags = question.getTags();
tags.add("malicious-tag");
tags.remove("java");

// Tags are now corrupted
```

**Fix: Return Immutable Copy**
```java
public Set<String> getTags() {
    return Collections.unmodifiableSet(tags);
}

// OR create defensive copy
public Set<String> getTags() {
    return new HashSet<>(tags);
}

// OR best: make tags immutable from construction
public class Question {
    private final Set<String> tags;

    public Question(Long id, String title, String body, Long authorId, Set<String> tags) {
        // ...
        this.tags = Set.copyOf(tags);  // ✅ Immutable copy
    }

    public Set<String> getTags() {
        return tags;  // ✅ Already immutable
    }
}
```

---

### 🔴 ISSUE #4: No Vote Deduplication - Users Can Vote Multiple Times (CRITICAL)

**Location**: System-wide - Vote model exists but not used

**Problem**: No tracking of who voted on what

```java
// Currently anyone can call incrementVotes() unlimited times
question.incrementVotes();  // ✅ +1
question.incrementVotes();  // ✅ +1
question.incrementVotes();  // ✅ +1
// Same user voted 3 times!
```

**Impact**: Vote manipulation, no vote change support

**Fix: Add Vote Tracking Service**
```java
@Service
public class VoteService {
    private final ConcurrentHashMap<String, Vote> votes = new ConcurrentHashMap<>();
    // votes key: userId:targetId:targetType

    public boolean vote(Long userId, Long targetId, VoteType targetType,
                       VoteValue voteValue, Question question) {
        String voteKey = userId + ":" + targetId + ":" + targetType;

        // Check existing vote
        Vote existingVote = votes.get(voteKey);

        if (existingVote != null) {
            if (existingVote.value() == voteValue) {
                // Same vote, ignore (or could remove vote)
                return false;
            } else {
                // ✅ Vote changed: need to apply delta of 2
                // Previous: UP (+1), New: DOWN (-1) => Delta: -2
                votes.put(voteKey, Vote.create(userId, targetId, targetType, voteValue));

                if (voteValue == VoteValue.UP) {
                    question.incrementVotes();
                    question.incrementVotes();  // +2 total
                } else {
                    question.decrementVotes();
                    question.decrementVotes();  // -2 total
                }
                return true;
            }
        }

        // ✅ New vote
        votes.put(voteKey, Vote.create(userId, targetId, targetType, voteValue));

        if (voteValue == VoteValue.UP) {
            question.incrementVotes();
        } else {
            question.decrementVotes();
        }

        return true;
    }

    public boolean removeVote(Long userId, Long targetId, VoteType targetType,
                             Question question) {
        String voteKey = userId + ":" + targetId + ":" + targetType;
        Vote removed = votes.remove(voteKey);

        if (removed != null) {
            // ✅ Reverse the vote
            if (removed.value() == VoteValue.UP) {
                question.decrementVotes();
            } else {
                question.incrementVotes();
            }
            return true;
        }

        return false;
    }

    public Optional<Vote> getUserVote(Long userId, Long targetId, VoteType targetType) {
        String voteKey = userId + ":" + targetId + ":" + targetType;
        return Optional.ofNullable(votes.get(voteKey));
    }
}
```

---

### 🟡 ISSUE #5: No Reputation Tracking for Votes (MEDIUM Priority)

**Location**: System-wide

**Problem**: Votes don't affect user reputation

```java
// User posts question
Question q = questionService.postQuestion(authorId, ...);

// 10 users upvote
for (int i = 0; i < 10; i++) {
    q.incrementVotes();
}

// ❌ Author's reputation unchanged!
// Should be +50 (10 upvotes × 5 points each)
```

**Fix: Link Votes to Reputation**
```java
@Service
public class VoteService {
    private final UserService userService;

    private static final int QUESTION_UPVOTE_REPUTATION = 5;
    private static final int QUESTION_DOWNVOTE_REPUTATION = -2;
    private static final int ANSWER_UPVOTE_REPUTATION = 10;
    private static final int ANSWER_DOWNVOTE_REPUTATION = -2;

    public boolean voteQuestion(Long userId, Long questionId, VoteValue voteValue) {
        Question question = questionService.getQuestion(questionId)
            .orElseThrow(() -> new NotFoundException("Question not found"));

        // Apply vote to question
        boolean voteApplied = vote(userId, questionId, VoteType.QUESTION, voteValue, question);

        if (voteApplied) {
            // ✅ Update author's reputation
            User author = userService.getUser(question.getAuthorId())
                .orElseThrow(() -> new NotFoundException("Author not found"));

            int reputationChange = voteValue == VoteValue.UP
                ? QUESTION_UPVOTE_REPUTATION
                : QUESTION_DOWNVOTE_REPUTATION;

            author.addReputation(reputationChange);
        }

        return voteApplied;
    }
}
```

---

### 🟢 ISSUE #6: Inefficient Search - Full Table Scan (LOW Priority)

**Location**: `service/QuestionService.java:26-35`

**Problem**: Keyword search scans all questions

```java
public List<Question> searchByKeywords(String keywords) {
    String[] terms = keywords.toLowerCase().split("\\s+");
    return questions.values().stream()  // ❌ O(n) scan
            .filter(q -> {
                String searchText = (q.getTitle() + " " + q.getBody()).toLowerCase();
                return Arrays.stream(terms).anyMatch(searchText::contains);
            })
            .sorted(Comparator.comparingInt(Question::getVoteCount).reversed())
            .collect(Collectors.toList());
}
```

**Impact**: Slow with large question counts

**Recommendation: Add Inverted Index**
```java
@Service
public class QuestionService {
    private final ConcurrentHashMap<Long, Question> questions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Long>> invertedIndex = new ConcurrentHashMap<>();

    public Question postQuestion(Long authorId, String title, String body, Set<String> tags) {
        Long id = questionIdCounter.incrementAndGet();
        Question question = new Question(id, title, body, authorId, tags);
        questions.put(id, question);

        // ✅ Build inverted index
        String searchText = (title + " " + body).toLowerCase();
        String[] words = searchText.split("\\W+");
        for (String word : words) {
            if (word.length() > 2) {  // Ignore short words
                invertedIndex.computeIfAbsent(word, k -> ConcurrentHashMap.newKeySet())
                            .add(id);
            }
        }

        return question;
    }

    public List<Question> searchByKeywords(String keywords) {
        String[] terms = keywords.toLowerCase().split("\\s+");

        // ✅ Use inverted index for O(1) lookup per term
        Set<Long> matchingIds = Arrays.stream(terms)
                .filter(term -> term.length() > 2)
                .map(term -> invertedIndex.getOrDefault(term, Set.of()))
                .reduce((set1, set2) -> {
                    Set<Long> intersection = new HashSet<>(set1);
                    intersection.retainAll(set2);
                    return intersection;
                })
                .orElse(Set.of());

        return matchingIds.stream()
                .map(questions::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Question::getVoteCount).reversed())
                .collect(Collectors.toList());
    }
}
```

---

## Additional Recommendations

### 1. Add Comment System
```java
public class Comment {
    private final Long id;
    private final Long targetId;  // Question or Answer ID
    private final CommentType targetType;
    private final Long authorId;
    private final String content;
    private final Instant createdAt;

    // Constructor, getters...
}

@Service
public class CommentService {
    private final ConcurrentHashMap<Long, Comment> comments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<Long>> commentsByTarget = new ConcurrentHashMap<>();

    public Comment addComment(Long targetId, CommentType targetType,
                             Long authorId, String content) {
        Long id = commentIdCounter.incrementAndGet();
        Comment comment = new Comment(id, targetId, targetType, authorId, content);
        comments.put(id, comment);

        commentsByTarget.computeIfAbsent(targetId, k -> new ArrayList<>())
                       .add(id);

        return comment;
    }

    public List<Comment> getCommentsForTarget(Long targetId) {
        return commentsByTarget.getOrDefault(targetId, List.of()).stream()
                .map(comments::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
```

### 2. Add Badge/Achievement System
```java
public enum Badge {
    CURIOUS("Ask well-received question", 1),
    STUDENT("First question with score of 1 or more", 1),
    TEACHER("Answer a question with score of 1 or more", 1),
    COMMENTATOR("Leave 10 comments", 10),
    POPULAR_QUESTION("Question with 1000 views", 1000),
    // ...
}

@Service
public class BadgeService {
    public void checkAndAwardBadges(Long userId) {
        User user = userService.getUser(userId).orElseThrow();

        // Check various conditions and award badges
        if (user.getReputation() >= 100 && !hasBadge(userId, Badge.STUDENT)) {
            awardBadge(userId, Badge.STUDENT);
        }

        // More badge logic...
    }
}
```

### 3. Add Markdown Support
```java
@Service
public class MarkdownService {
    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String renderMarkdown(String markdown) {
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    public String sanitizeHtml(String html) {
        // Remove dangerous tags (script, iframe, etc.)
        return Jsoup.clean(html, Safelist.relaxed());
    }
}

// Usage in Question/Answer rendering
public String getBodyHtml() {
    return markdownService.renderMarkdown(this.body);
}
```

---

## Testing Recommendations

### Test #1: Concurrent Accept Answer
```java
@Test
void testConcurrentAcceptAnswer() throws InterruptedException {
    Question question = new Question(1L, "Title", "Body", 100L, Set.of("java"));
    Answer answer1 = new Answer(1L, 1L, "Answer 1", 200L);
    Answer answer2 = new Answer(2L, 1L, "Answer 2", 300L);

    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);

    // 50 threads try to accept answer1, 50 try answer2
    for (int i = 0; i < threadCount / 2; i++) {
        new Thread(() -> {
            try {
                question.acceptAnswer(answer1);
            } finally {
                latch.countDown();
            }
        }).start();

        new Thread(() -> {
            try {
                question.acceptAnswer(answer2);
            } finally {
                latch.countDown();
            }
        }).start();
    }

    latch.await();

    // ✅ Only ONE answer should be marked accepted
    int acceptedCount = 0;
    if (answer1.isAccepted()) acceptedCount++;
    if (answer2.isAccepted()) acceptedCount++;

    assertEquals(1, acceptedCount, "Only one answer should be accepted");
    assertNotNull(question.getAcceptedAnswerId());
}
```

### Test #2: Vote Deduplication
```java
@Test
void testVoteDeduplication() {
    VoteService voteService = new VoteService();
    Question question = new Question(1L, "Title", "Body", 100L, Set.of("java"));

    Long userId = 200L;

    // User votes up
    voteService.voteQuestion(userId, question.getId(), VoteValue.UP);
    assertEquals(1, question.getVoteCount());

    // User tries to vote up again
    voteService.voteQuestion(userId, question.getId(), VoteValue.UP);
    assertEquals(1, question.getVoteCount(), "Should not allow duplicate vote");

    // User changes to down vote
    voteService.voteQuestion(userId, question.getId(), VoteValue.DOWN);
    assertEquals(-1, question.getVoteCount(), "Should change from +1 to -1");
}
```

---

## Conclusion

The Stack Overflow clone has good basic structure but **CRITICAL thread-safety issues** in accept answer logic, body updates, and lacks vote deduplication. These issues must be fixed before production deployment.

### Severity Assessment
🔴 **CRITICAL**: Non-atomic accept answer operation
🔴 **CRITICAL**: Non-atomic body update, missing volatile
🔴 **CRITICAL**: Tags mutability leak
🔴 **CRITICAL**: No vote deduplication (vote manipulation)
🟡 **MEDIUM**: No reputation tracking for votes
🟢 **LOW**: Inefficient full-text search

### Action Items
1. ✅ **IMMEDIATELY** fix accept answer atomicity
2. ✅ **IMMEDIATELY** make body/updatedAt volatile and synchronized
3. ✅ **IMMEDIATELY** fix tags mutability leak
4. ✅ **IMMEDIATELY** implement vote deduplication with VoteService
5. ⚠️ **Add reputation changes** linked to votes
6. ⚠️ **Add inverted index** for efficient search
7. ⚠️ **Add comment system** for discussions
8. ⚠️ **Add badge/achievement system** for gamification

**Verdict**: REJECTED for production. Fix critical thread-safety and vote deduplication issues.

### Recommended Timeline
**Week 1**: Fix all critical issues (accept answer, body updates, mutability, vote deduplication)
**Week 2**: Add comprehensive concurrency tests, reputation tracking
**Week 3**: Add comment system, badge system
**Week 4**: Optimize search with inverted index, add markdown support

**DO NOT deploy until all Week 1 items are complete and tested.**
