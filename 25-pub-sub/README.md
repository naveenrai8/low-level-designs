# Topic 25: Pub-Sub

"Design a pub-sub system" sounds easy: keep a list of subscribers and call each one on publish. The interviewer is waiting for three questions: what happens when one subscriber is slow, what happens when a message fails, and how do you keep order when many workers read in parallel.

A staff answer gives each subscriber group its own read position, retries a bounded number of times and then parks the message in a dead-letter queue, and keeps same-key messages in order by never running two of them at once.

## Table of Contents

0. [Words used in this topic](#0-words-used-in-this-topic)
1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Topic** | A named, append-only list (log) of messages. Publishers add to the end. Nothing is removed when it is read. |
| **Offset** | A message's position in the topic: 0, 1, 2, ... |
| **Group** | A set of consumers that share one read position. Two groups on one topic each see every message. |
| **Ack / nack** | "Done, don't send it again" / "I failed, send it again". |
| **Ack deadline** | How long a consumer has to ack. After that the broker assumes it died and counts a failure. |
| **Attempt** | One delivery of a message. Attempt 1 is the first try. |
| **DLQ** | Dead-letter queue: where a message goes after its last allowed attempt fails, so it stops blocking everything. |
| **Key** | A label (like an order id). Messages with the same key must be processed in publish order. |
| **Lag** | How many messages a group has not finished yet. |

## 1. The Problem

What is really probed: independent progress per subscriber, at-least-once delivery with a bounded retry, and ordering that survives parallelism.

**v1:** create topics, subscribe groups, publish, poll, and keep a separate offset per group.

Clarifying questions with assumed answers:

| Question | Assumed answer |
|---|---|
| Delivery guarantee? | At-least-once. Handlers must be idempotent. |
| Does a slow group slow down others? | Never. Each group has its own position. |
| Is the log kept after reading? | Yes (no retention in v1), so a group can rewind. |
| Ordering? | Per key only, not across keys. |

Out of scope: persistence, network, partitions across machines, exactly-once, retention and compaction.

## 2. How It Solves It

### Entities and invariants

- `Topic`: the log plus its groups, behind one lock.
- `Subscription`: one group's cursor, retry queue, in-flight set and DLQ.
- Invariant 1: a message is in exactly one place per group (unread, waiting, in flight, done, or dead), so nothing is lost.
- Invariant 2: a message is delivered at most `maxAttempts` times.
- Invariant 3: per key at most one message is in flight as far as the broker knows, and a key's messages go out in offset order. Caveat: after an ack deadline expires the broker frees the key, so a handler that is still running (a zombie) can overlap with the next message of that key. Same-key order is only guaranteed while handlers finish inside their deadline; idempotent handlers make the overlap safe.

### The interface

```java
public interface PubSub {
    void createTopic(String topic);
    void createTopic(String topic, int maxLag);                 // F3: lag limit
    void subscribe(String topic, String group, SubscriptionConfig config);
    long publish(String topic, String key, String payload);     // throws BackpressureException at the lag limit
    Optional<Delivery> poll(String topic, String group);
    Delivery take(String topic, String group) throws InterruptedException;
    boolean ack(String topic, String group, Delivery d);        // false = stale receipt, never throws
    boolean nack(String topic, String group, Delivery d);
    void seek(String topic, String group, long offset);         // moves only this group
    int lag(String topic, String group);
    long position(String topic, String group);                  // next-to-read offset
    List<DeadLetter> deadLetters(String topic, String group);
}
```

### Picture

```
 publisher --> [ topic log: 0 1 2 3 4 5 ... ]
                    ^cursor A        ^cursor B       (each group has its own)
 group A --> Subscription A: ready{retries} inFlight{msg+deadline} busy{key->offset} dead[]
 group B --> Subscription B: (own copy of the same state)
 ConsumerPool(A, 4 threads): take -> handler -> ack | nack
```

### Per-subscriber offsets

Timeline: A reads fast, B is slow, then A rewinds.

| Step | What happens | A position | B position |
|---|---|---|---|
| 1 | 5 messages published | 0 | 0 |
| 2 | A drains all 5 | 5 | 0 |
| 3 | B reads and acks 1 | 5 | 1 |
| 4 | A seeks to 1 (replay) | 1 | 1 |
| 5 | B keeps going | 1 | 2 |

The cursor lives inside each `Subscription`, so no operation on A can touch B. A group's `seek` also forgets only that group's in-flight work.

### Ack, redelivery and DLQ (F1)

Timeline with `maxAttempts = 3` and a consumer that keeps failing:

| Time | Event | Result |
|---|---|---|
| t=0 | poll | attempt 1 delivered, deadline t=10s |
| t=4 | nack | back in the retry queue |
| t=4 | poll | attempt 2 delivered |
| t=14 | no ack, deadline passed | counts as failure, retry queue |
| t=14 | poll | attempt 3 delivered |
| t=20 | nack | attempt 3 >= max, so DLQ with attempts=3 |
| t=20 | poll | nothing: attempt 4 never happens |

Every group operation (poll, ack, nack, seek, lag, position, deadLetters, and publish's lag check) first runs `expire`, which turns overdue in-flight messages into failures. There is no timer thread. A blocked `take()` sleeps until the earliest in-flight deadline (a timed wait computed from the `Ticker`), so even a quiet topic redelivers a hung handler's message. Tests move a fake `Ticker` and call the package-private `wake()` seam. Each delivery has a broker-wide unique token (so a receipt from another group or topic is stale too), so a late ack from attempt 1 cannot settle attempt 2 (it returns false).

### Ordering per key with parallel consumers (F2)

Timeline with 2 workers, keys a and b, messages 0=a, 1=a, 2=b:

| Step | Worker 1 | Worker 2 | Why |
|---|---|---|---|
| 1 | takes 0 (a) | | key a becomes busy |
| 2 | | takes: sees 1 (a) is busy, parks it, takes 2 (b) | different key runs in parallel |
| 3 | acks 0 | | key a is free |
| 4 | takes 1 (a) | | a's second message goes only now, so order holds |

Rule: `busy` maps a key to its in-flight offset, and the retry queue is scanned lowest offset first. A failed message goes back to the retry queue keyed by its offset, so it is retried before its successor. The workers (`ConsumerPool`) need no ordering logic at all. Cost: `poll` scans the retry queue under the topic lock, so with one hot key that parks many messages each poll is O(parked); one hot key does not block other keys, but it does make polling slower. Production fix: a queue per key.

### Backpressure (F3, coded)

`createTopic(topic, maxLag)`: `publish` checks every group's lag under the same lock as the append. If any group has `lag >= maxLag`, it throws `BackpressureException` and appends nothing. The producer decides: retry later, drop or shed. This is a bounded queue per group, see [Topic 22](../22-blocking-queue-thread-pool/) for the blocking variant.

### Concurrency model

- Shared state: the log, and each group's cursor, retry queue, in-flight map and DLQ.
- Primitive: one `ReentrantLock` per topic, with one `Condition` for "something changed" (publish, ack, nack, expiry, seek).
- Why one lock: publish must read all groups' lag and append atomically, and poll must read the log. One lock means no lock ordering to get wrong (see [Java-poc locks](../../internal-pocs/Java-poc/concurrency/16-reentrantlock/)).
- Handlers run outside the lock, so a slow handler never blocks the broker.
- Cost: groups of one topic contend on one lock. Fine in-process.

### Code and tests

| File | What it is |
|---|---|
| [PubSub.java](src/main/java/com/lld/pubsub/PubSub.java) | Interface and error contract |
| [Topic.java](src/main/java/com/lld/pubsub/Topic.java) | Log, lock, publish with lag check |
| [Subscription.java](src/main/java/com/lld/pubsub/Subscription.java) | Cursor, retries, deadlines, DLQ, key ordering |
| [ConsumerPool.java](src/main/java/com/lld/pubsub/ConsumerPool.java) | Worker threads: take, handle, ack or nack |
| [PubSubTest.java](src/test/java/com/lld/pubsub/PubSubTest.java) | 23 single-thread tests: offsets, redelivery, DLQ, seek, lag, backpressure |
| [ConcurrencyTest.java](src/test/java/com/lld/pubsub/ConcurrencyTest.java) | 10 multi-thread tests: parallelism, ordering, shutdown, Errors, deadline wake-up |

## 3. The Gaps

### F1: ack, redelivery, DLQ (coded)

Done above. Learn more: [delivery semantics](../../internal-pocs/event-processing-poc/core/03-delivery-semantics/), [DLQ and retry patterns](../../internal-pocs/event-processing-poc/core/10-dlq-retry-patterns/), [idempotency](../../internal-pocs/event-processing-poc/core/09-idempotency-dedup/). Not coded: retry backoff (a message is retried immediately) and replaying the DLQ.

### F2: per-key order with parallel consumers (coded)

Done above. Learn more: [ordering guarantees](../../internal-pocs/event-processing-poc/core/04-ordering-guarantees/), [ordering at scale](../../internal-pocs/event-processing-poc/core/11-ordering-at-scale/). Real brokers (Kafka) get the same result by hashing the key to a partition with one consumer per partition. Here one hot key stays serial but does not block other keys.

### F3: backpressure (coded as a lag limit)

Delta for a real system: block the producer with a timeout instead of throwing, or shed by dropping the oldest. Also add a per-consumer prefetch limit (Pub/Sub "flow control").

### Misconceptions and trade-offs

| Myth or trade-off | Truth |
|---|---|
| "Ack = exactly once" | No. A crash after the handler but before the ack causes a repeat, so handlers must be idempotent. |
| "Retry forever is safest" | A poison message then loops forever. The cap plus DLQ keeps the group moving. |
| "A slow group needs a bigger buffer" | Lag per group is the signal. Cap it and tell the producer. |
| A late ack is harmless | It is refused (returns false), otherwise it could settle a newer attempt. |
| Order across keys | Not promised. Only same-key order. |
| Not to use | If you need global order, run one consumer; parallel keys cannot give it. |

`ConsumerPool` rules: every delivery is settled (a handler throwing any Exception or Error is nacked and the worker lives on; a VM-level error is nacked then rethrown); `close()` interrupts a running handler, which is nacked and costs one attempt; a handler that finishes after its deadline is "processed but dead-lettered or redelivered", its ack is refused and counted in `refusedAcks()`. Seeking back past a dead letter that fails again replaces its DLQ entry (one entry per offset).

Known limits: the lag counts messages, not bytes; `lag` casts to `int`; the log never shrinks; a handler that blocks forever holds its key until the deadline passes (then a second worker may run the retry while the first is still running, which is exactly why handlers need idempotency).

### Production delta

Persist the log and the offsets, partition by key across brokers, make the ack deadline renewable (lease extension), add retry backoff and a DLQ topic with replay, add retention, and use consumer-group rebalancing. A DLQ in production is itself a topic.

## 4. Official References

- [Kafka documentation: topics, partitions, consumer groups](https://kafka.apache.org/documentation/#intro_concepts_and_terms)
- [Google Cloud Pub/Sub: subscription overview](https://cloud.google.com/pubsub/docs/subscription-overview)
- [Google Cloud Pub/Sub: ack deadlines and lease management](https://cloud.google.com/pubsub/docs/lease-management)
- [Google Cloud Pub/Sub: message ordering](https://cloud.google.com/pubsub/docs/ordering)
- [Amazon SQS: dead-letter queues](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)
- Kleppmann, Designing Data-Intensive Applications, ch. 11 (Stream Processing: logs, acknowledgements and redelivery).
- In this repo: [Topic 22 blocking queue](../22-blocking-queue-thread-pool/), [Java-poc executors](../../internal-pocs/Java-poc/concurrency/30-executor-framework/).

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-5 | Clarify: at-least-once, per-key order, scale. Say what is out of scope. |
| 5-12 | Interface and the log with a cursor per group. |
| 12-20 | Code publish/poll/ack for v1. Explain why the offset is per group. |
| 20-30 | F1: attempts, deadline, DLQ. Draw the timeline. |
| 30-38 | F2: busy-key map. Say why workers need no ordering code. |
| 38-45 | F3 lag limit, production delta, how you would test it without sleeping. |

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Offsets | Per-group cursor | Also explains rewind safety and stale receipts |
| Failure | Retries | Bounded attempts, exact DLQ count, never N+1, never lost |
| Ordering | "Use one thread" | Busy-key map: parallel across keys, serial per key |
| Testing | Hammer test | Barrier and latch proofs plus a fake ticker, no sleeps |
| Production | "Use Kafka" | Partitioning, lease extension, backoff, DLQ replay |

## 7. Key Takeaways

- Each group owns its offset, so a slow or rewinding group never affects another.
- Delivery is at-least-once. A cap on attempts plus a DLQ keeps one bad message from blocking a group.
- A unique token per delivery makes late acks harmless.
- Same-key order comes from "one in flight per key", not from the number of threads.
- Time is injected, so deadline tests are exact and instant.

[← Topic 24: KV Store with TTL and Transactions](../24-kv-store-ttl-transactions/) · [Index](../README.md) · [Topic 26: Connection Pool →](../26-connection-pool/)

**Self-grade: STRONG.** Remaining gap: no retry backoff or DLQ replay in code, and one lock per topic is untested at high contention.
