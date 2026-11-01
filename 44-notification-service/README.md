# Topic 44: Notification Service

"Design a notification service" sounds like three `send()` methods. The interviewer is waiting for what breaks: the same request arrives twice, the user is asleep, the provider is down, one user gets 500 messages in a minute, and a name like `<script>` lands in an HTML email.

A staff answer treats a notification as a stored record with a status, sends it from a worker, and decides in a fixed order: opt-out, quiet hours, rate limit, send, retry, give up.

## Table of Contents

[0 Words](#0-words-used-in-this-topic) · [1 Problem](#1-the-problem) · [2 How It Solves It](#2-how-it-solves-it) · [3 Gaps](#3-the-gaps) · [4 References](#4-official-references) · [5 Timeline](#5-45-minute-interview-timeline) · [6 Rubric](#6-senior-vs-staff-rubric) · [7 Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Channel** | One way to reach a person: email, SMS, push. |
| **Strategy** | A design pattern: one interface, many swappable implementations. Here `Channel` is the interface and email, SMS and push are the strategies. |
| **Template** | A message with named holes: `Hi {{name}}`. |
| **Escaping** | Rewriting special characters so a value is safe in its medium. In HTML, `<` becomes `&lt;`. |
| **Quiet hours** | A daily window (say 22:00 to 07:00) in the user's own time zone when we do not disturb them. |
| **Half-open** | The start is inside the window and the end is outside it. At exactly 07:00 quiet hours are over. |
| **DST** | Daylight saving time: twice a year the local clock jumps forward (a *gap*) or back (an *overlap*). |
| **Transient / permanent failure** | Transient may go away (timeout). Permanent never will (invalid address). |
| **Backoff** | Waiting longer after each failure: 1s, 2s, 4s, up to a cap. |
| **Jitter** | A random part taken off each wait, so many failures do not retry at the same instant. Ours is seeded, so tests are repeatable. |
| **DLQ** | Dead letter queue: where messages we gave up on go, with the reason, for a person to look at. |
| **Idempotency key** | A caller-chosen string. Same key + same request means "do it once". Same key + different request is an error. See [Topic 11](../11-money-ids-idempotency/). |
| **At-least-once** | A message may arrive twice but, with a durable store, is never lost. **At-most-once** is the opposite. |
| **Rate limit** | At most N messages per user per minute. We reuse [Topic 21](../21-rate-limiter/). Tests use virtual time ([Topic 10](../10-time-and-testability/)). |

---

## 1. The Problem

Many parts of a product want to tell users things. Calling the email provider directly from each one leaks provider details everywhere, repeats the retry code, and makes it easy to wake someone at 3 am or send the same code twice.

### v1 requirements (say these out loud)

- Three channels behind one `Channel` interface.
- Templates with named placeholders. A missing variable is an error.
- Values are escaped for the channel. SMS is cut to 160 characters.
- `submit(idempotencyKey, request)` stores the request and returns an id. A worker (`runDue`) sends.

### Clarifying questions (assumed answers)

| Question | Assumed answer |
|---|---|
| Is "sent" the same as "delivered"? | No. SENT means the provider accepted it. Delivery receipts are out of scope. |
| What is lost if we crash? | Never a message. We may send one twice (at-least-once). |

**Out of scope:** (urgent skips quiet hours only; opt-out and the rate limit still apply) real providers, delivery receipts, unsubscribe links, batching and digests, durable storage, template editing UI.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Invariant |
|---|---|
| `Notification` | An immutable snapshot with a status: PENDING, SENT, DEAD or SUPPRESSED. Only PENDING changes. `attempts` counts real channel calls only. |
| `Template` | Fills `{{name}}` in one pass. Missing variable throws. A value that looks like `{{x}}` is not expanded again. |
| `QuietHours` | Half-open window, may cross midnight, always judged by the user's local time. |
| Idempotency record | Written together with the PENDING notification, before any send. One per key. |

```
 submit --> [PENDING] --runDue--> opted out? ---------------------------> SUPPRESSED
                |                  quiet hours (normal only)? -> PENDING, later
                |                  rate limited? -------------> PENDING, later
                |                  send ok? ------------------> SENT
                |                  permanent failure? --------> DEAD (DLQ)
                +<-- transient failure, attempts left (backoff + jitter)
                                   transient, attempts used up -> DEAD (DLQ)
```

### The interface

```java
public interface Channel {
    ChannelType type();
    String escape(String value);   // email: HTML-escape. sms/push: unchanged
    String fit(String body);       // sms: cut to 160. others: unchanged
    void send(String address, String body, String idempotencyKey);  // throws ChannelException
}

SubmitResult submit(String idempotencyKey, NotificationRequest request);  // never sends
int runDue();                                                             // sends what is due
Optional<Notification> find(String id);
List<DeadLetter> deadLetters();
```

Errors: `InvalidRequestException` (fix the request; REST 400), `IdempotencyConflictException` (REST 422). A channel throws `ChannelException` that says if it is transient.

### Templating and escaping

Escaping is done on the **values**, never on the template, and by the channel, because only the channel knows the medium. The ampersand is replaced first; otherwise the `&` we add for `<` would be escaped again (`&amp;lt;`). SMS and push are plain text, so HTML escaping would show `&lt;` to the user, which is wrong.

SMS policy: the limit is 160 characters. A longer body is cut to 157 plus `...`. We cut instead of reject because a clipped alert beats no alert. A cut never splits an emoji (it backs up one character). Emoji or accents switch real SMS to a 70-character limit (commonly cited figure, not sourced here); we do not model that.

### Quiet hours, worked example

User in New York, quiet hours 22:00 to 02:30, on the night clocks spring forward (8 March 2026, 02:00 becomes 03:00).

| Real time (UTC) | Local time | What happens | Result |
|---|---|---|---|
| 04:00Z on the 8th | 23:00 on the 7th | A normal message arrives. 23:00 is inside the window. | Held |
| (the end we want) | 02:30, does not exist | The clock jumps 02:00 to 03:00, so there is no 02:30. | End = the jump itself, 07:00Z |
| 07:00Z | 03:00 | Local time is past 02:30, so not quiet. | Sent |

The fall-back night is the other trap (1 Nov 2026, 01:30 happens twice, quiet end 01:30):

| Real time (UTC) | Local time | What happens | Result |
|---|---|---|---|
| 03:00Z | 23:00 EDT | Inside the window. End 01:30 happens first at 05:30Z. | Held until 05:30Z |
| 06:10Z | 01:10 EST (second pass) | Inside the window again. We pick the 01:30 that is still ahead: 06:30Z. | Held until 06:30Z |

How: whether it is quiet now is decided by the local time at that instant, so DST needs no special case there. Only the end time needs care: in a gap use the transition instant, in an overlap prefer the offset we are on now, so the answer is never in the past. Urgent messages (security alerts, fraud) bypass quiet hours. This is documented in `Priority`, and urgent still respects opt-out.

### Retry timeline, worked example

Max 5 attempts, base 1s, cap 8s, transport fails twice with a 503 then works:

| Attempt | Call result | Wait before next (equal jitter) | Status after |
|---|---|---|---|
| 1 | 503 (transient) | 0.5s to 1s | PENDING, attempts 1 |
| 2 | 503 (transient) | 1s to 2s | PENDING, attempts 2 |
| 3 | OK | none | SENT, attempts 3 |

A permanent failure (invalid address) goes to DEAD after attempt 1. If every attempt fails, attempt 5 moves it to DEAD with reason "retries exhausted" and attempts = 5.

The wait is half the backoff plus a random amount up to the other half. Full randomness (0 to backoff) can retry almost instantly; no jitter synchronises a crowd. Equal jitter is the middle path.

### Idempotency, and what a crash does

`submit` uses `ConcurrentHashMap.computeIfAbsent`, which runs the "create it" code at most once per key even if 32 threads race. The id supplier runs once, so exactly one notification exists. Same key + equal request returns the same id with `replay=true`. Same key + different request throws (REST 422, as the IETF draft section 2.7 asks and as Topic 31 does; the draft keeps 409 for a retry that arrives while the first request is still running, which cannot happen here because `submit` finishes before it returns). A repeat is answered from the key record before the request is rendered, so a replay still works if the user or template changed meanwhile.

The key is stored **before** any send, and SENT is stored **after**. Crash between the send and the SENT write: after restart the notification is still PENDING, so it is sent again. That is **at-least-once**: a duplicate is possible, a loss is not (true only with a durable store; this in-memory version loses everything if the process dies). We pass the notification id to the provider as its own idempotency key so a decent provider drops the duplicate. If we wrote "sending" first and never resent, a crash would lose the message: at-most-once. For notifications losing is usually worse than a duplicate.

An invalid request never claims a key, so the caller can fix the body and reuse the key.

### Rate limiting (F3, coded)

`KeyedRateLimiter` from [Topic 21](../21-rate-limiter/), key = user id, token bucket. When the user is over the limit the message is **deferred** by the limiter's `retryAfter`, not dropped, and no attempt is used. Policy choice: urgent messages are limited too, because the limit protects users from a buggy caller; a real system might give urgent its own bucket. The limiter's `Ticker` is the same fake clock as the service in tests, so the whole thing runs in virtual time.

### Concurrency model

Shared state: the key map and the notification map (both concurrent maps). Invariant: one notification per key. Primitive: `computeIfAbsent`. `runDue` is `synchronized`: one sender at a time, which keeps rate-limit and jitter order fixed. Why: simple and correct; production shards by user (see Gaps).

### Code and tests

| File | What |
|---|---|
| `src/main/java/com/lld/notify/Channel.java` + `EmailChannel`, `SmsChannel`, `PushChannel` | The Strategy and its three implementations |
| `Template.java`, `QuietHours.java`, `RetryPolicy.java` | Templating, the window and DST, backoff with seeded jitter |
| `NotificationService.java` | Submit, rules, retry, DLQ, idempotency, rate limit |
| `ChannelAndTemplateTest`, `QuietHoursTest`, `NotificationServiceTest`, `ReviewFixesTest` | 53 tests, all in virtual time |
| `app/.../notify/NotificationController.java` | `POST /notifications` (202), `GET /notifications/{id}` |

---

## 3. The Gaps

### F1: preferences and quiet hours (coded)

Preferences are read at send time, not submit time, so "I opted out ten minutes ago" is honoured for messages still waiting. Delta for production: per-category preferences (marketing vs security), and a legal rule that some messages (password reset) ignore opt-out.

### F2: retry, DLQ, idempotency (coded)

Delta: a real queue instead of a scan in `runDue`, and a DLQ with a "redrive" button. See [event-processing core: DLQ and retry](../../internal-pocs/event-processing-poc/core/10-dlq-retry-patterns/) and [idempotency and dedup](../../internal-pocs/event-processing-poc/core/09-idempotency-dedup/).

### F3: rate limiting (coded)

Delta: limit per user + channel, or give urgent its own bucket; limit per tenant too.

### Common mistakes

| Mistake | Why it is wrong |
|---|---|
| Drop messages in quiet hours or over the rate limit | Silent loss. Defer. |
| Compare local time with `LocalTime.now()` | Wrong zone, and untestable. Inject a `Clock`. |
| Retry without jitter | A recovered provider is hit by every client at once. |

### Robustness rules

- A transport that throws something unexpected is treated as a transient failure: it counts an attempt, retries, then reaches the DLQ. Without this, one crashing message would stay first in line forever and block everyone behind it.
- A user who lost the address after submit is DEAD with reason "no address for EMAIL" (visible in the DLQ, because someone may want to fix the data). SUPPRESSED is kept for "the user said no".
- Messages due at the same time go out in submit order (a sequence number), not by id text, where `n10` sorts before `n2`.
- The app's rate limiter runs on a clock derived from wall time, so it is not monotonic: a clock step back makes a limited user wait longer, a step forward lets at most one extra bucket through. Fine for a demo; production uses `Ticker.system()`.

### Trade-offs and when not to

A scan of all notifications in `runDue` is O(n); fine here, use a delay queue or a sorted store at scale. A single `synchronized` sender caps throughput. If you need exactly-once delivery, you cannot get it from the sender alone: you need provider-side dedup with the key we pass.

### Production and distributed delta

- Store notifications and keys in a database; the key becomes a unique index, and the "claim" is an insert.
- Shard workers by user id so one user's order and rate limit stay on one worker.
- Send with a lease: a worker takes a notification for N seconds; a crash lets another take it (at-least-once).
- Per-channel circuit breaker ([Topic 27](../27-circuit-breaker-and-retry/)) so a dead SMS provider does not stall email. Keep the JDK time zone data current.

---

## 4. Official References

- [java.time.zone.ZoneRules (getValidOffsets, getTransition)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/zone/ZoneRules.html)
- [java.time.ZonedDateTime.ofLocal: how gaps and overlaps are resolved](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html)
- [AWS Architecture Blog: Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
- [IETF draft: The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Apple: Generating a remote notification (4 KB payload limit for push)](https://developer.apple.com/documentation/usernotifications/generating-a-remote-notification)
- [OWASP: Cross Site Scripting Prevention Cheat Sheet (output encoding)](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html)
- Related guides: [Topic 21 rate limiter](../21-rate-limiter/), [Topic 11 idempotency](../11-money-ids-idempotency/), [Topic 10 time](../10-time-and-testability/)
- Book: Kleppmann, Designing Data-Intensive Applications, ch. 11 (stream processing: delivery guarantees)

---

## 5. 45-Minute Interview Timeline

- 0-5 min: ask the section 1 questions; say sent is not delivered.
- 5-12: draw submit then worker, the status diagram and the `Channel` interface.
- 12-20: code `Template` (missing variable is an error) and one channel with escaping.
- 20-30: order of rules, quiet hours with a zone and DST, the retry table.
- 30-38: idempotency, key before send, what a crash does.
- 38-45: rate limit by reuse, DLQ, production delta.

---

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Channels | Interface with three classes | Escaping and length belong to the channel; says why SMS must not HTML-escape |
| Time | Uses the user's zone | Handles the DST gap and overlap, injects `Clock`, tests both nights |
| Failure | Retries | Transient vs permanent, seeded jitter, DLQ with reason and count |
| Idempotency | Dedups by key | Key before send, crash analysis, at-least-once, 422 on payload mismatch |
| Limits | A counter | Reuses a tested limiter, defers not drops, states urgent policy |

---

## 7. Key Takeaways

- Accept fast, send later. The status is the contract.
- Defer, never drop: quiet hours and rate limits only change the time.
- Quiet hours are a local-time question; DST only bites when computing the end.
- Permanent failures skip retries; transient ones back off with jitter.
- Store the idempotency key before sending: you get at-least-once, and pass the id to the provider to absorb the duplicate.

[← Topic 43: Feature Flags](../43-feature-flags/) · [Index](../README.md) · [Topic 45: Content Popularity](../45-content-popularity/)

**Self-grade: STRONG.** Remaining gap: no durable store or real queue (the scan in `runDue` is O(n)), urgent shares the normal rate-limit bucket, and SMS uses the simple 160-character rule without UCS-2.
