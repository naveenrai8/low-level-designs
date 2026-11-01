# Topic 35: Order-Matching Engine

"Design the core of a stock exchange" sounds huge. The core is small: a list of people who want to buy, a list of people who want to sell, and a rule for who trades with whom. The interviewer is waiting for what breaks: prices as `double`, trading at the wrong price, a "fill all or nothing" order that half-fills, locks around the book, and a replay that gives a different answer from the live run.

A staff answer uses integer ticks, a sorted map of FIFO queues, trades at the resting order's price, checks the whole order before it changes anything, runs the book on ONE thread fed by a queue, and logs every accepted command so the run can be replayed exactly.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### Words first

- A *bid* is an order to BUY: "I will pay at most this price". An *ask* is an order to SELL: "I want at least this price".
- The *book* is the two waiting lists: bids (highest price first) and asks (lowest price first).
- The *spread* is the gap between the best (highest) bid and the best (lowest) ask. In a healthy book the best bid is always BELOW the best ask.
- An incoming order *crosses* when it can trade right now: a buy at 102 crosses an ask at 101 or 102, not 103.
- *Price-time priority*: the best price trades first, and among equal prices the order that arrived first trades first (FIFO, first in first out).
- A *partial fill* is when only part of an order trades. The *taker* is the incoming order, the *maker* is the order that was already waiting.
- *IOC* (immediate-or-cancel): trade what you can now, cancel the rest. *FOK* (fill-or-kill): trade ALL of it now, or do nothing. A *market* order has no price: it takes whatever is there.
- A *tick* is the smallest price step. Prices are whole numbers of ticks (`long`), quantities are whole units (`long`).

### Worked example first

The book has one bid and four asks. Within a price, `s1` arrived before `s2`.

| Side | Price | Orders in queue order |
|---|---|---|
| ASK | 103 | s4: 6 |
| ASK | 102 | s3: 5 |
| ASK | 101 | s1: 4, s2: 3 |
| BID | 99 | b1: 10 |

The spread is 99 to 101. A new order arrives: BUY 15 at limit price 102 (`t`).

| Step | What happens | Result |
|---|---|---|
| 1 | The best ask is 101, and 101 <= 102, so it crosses. `s1` is first in line. | Trade 4 at 101 (`s1`'s price). `t` has 11 left. |
| 2 | Same level, next in line is `s2`. | Trade 3 at 101. `t` has 8 left. The 101 level is empty and removed. |
| 3 | Next best ask is 102 (`s3`), 102 <= 102, crosses. | Trade 5 at 102. `t` has 3 left. |
| 4 | Next best ask is 103. 103 > 102 does not cross. Stop. | The remaining 3 rests as a bid at 102. |

Three trades: 4@101, 3@101, 5@102. The buyer was willing to pay 102 but paid 101 for the first 7: trades happen at the RESTING order's price. The book after:

| Side | Price | Orders |
|---|---|---|
| ASK | 103 | s4: 6 |
| BID | 102 | t: 3 |
| BID | 99 | b1: 10 |

Best bid 102 < best ask 103, so the book is not crossed. Quantity check for `t`: asked 15 = filled 12 + resting 3 + cancelled 0. The test is `LimitOrderBookTest.readmeExample`.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Instruments? | One book for one instrument. |
| Price and quantity type? | `long` ticks and `long` units. Never `double`. |
| Order types in v1? | Limit orders (good till cancelled). |
| Bad input? | `IllegalArgumentException`, and nothing is written (not the book, trades or log). |
| Out of scope | Self-trade prevention (ids are the only identity), accounts, fees, auctions ([Topic 34](../34-auction/)), stop orders, iceberg orders, a REST layer, market data feeds. |

---

## 2. How It Solves It

### Entities and invariants

| Entity | Meaning |
|---|---|
| `Command` | Sealed: `NewOrder` (limit, IOC, FOK, market), `Cancel`, `Replace`. Immutable, so a list of them is a history. |
| `Book` / `LimitOrderBook` | The interface and the single-thread implementation. |
| `Trade` | taker id, maker id, maker price, quantity, a trade number. |
| `Result` / `OrderView` | What a command did, and a read-only picture of an order. |
| `MatchingEngine` | One thread plus a bounded queue around a book (F2). |

- I1 (conservation): for every order, `original == filled + resting + cancelled`. Globally, quantity bought = quantity sold = quantity in the trade list.
- I2: the book is never crossed after any command (best bid < best ask).
- I3: no order with zero quantity rests, and no empty price level exists.
- I4: inside a price level the queue is in arrival order (FIFO). Only the arrival stamp decides, never a clock.
- I5: a rejected command changes nothing.
- `LimitOrderBook.checkInvariants()` checks I1 to I4 (plus that each order's `live` flag matches the real book) and the tests call it after every command of random flows.

### The interface and its error contract

```java
public interface Book {
    Result apply(Command command);            // IllegalArgumentException, nothing written: null command, blank id, null side/type, qty <= 0, price <= 0 (limit), market with a price, price*qty overflows a long, duplicate id (ids are never reused, even after fill or cancel), cancel/replace of an id that is not resting, replace to qty <= 0 or price <= 0
    List<OrderView> resting(Side side);       // priority order: best price first, then oldest
    List<OrderView> orders();                 // every order ever accepted
    List<Trade> trades();                     // oldest first
    List<Command> log();                      // accepted commands only; replay it to rebuild the book
    String snapshot();                        // text form of the book, equal books give equal strings
}
```

### Class and sequence diagrams

```
 Book <|-- LimitOrderBook
             bids: TreeMap<price, LinkedHashMap<id, Order>>   (reverse order: highest first)
             asks: TreeMap<price, LinkedHashMap<id, Order>>   (lowest first)
             orders: Map<id, Order>   trades: List<Trade>   log: List<Command>
 MatchingEngine --owns--> LimitOrderBook, ArrayBlockingQueue<Task>, ONE Thread

 producer-1 --submit(cmd)--+                                     +--> CompletableFuture<Result> (to the caller)
 producer-2 --submit(cmd)--+--> [ bounded queue ] --take()--> engine thread --> book.apply(cmd) --> log, trades
 producer-N --submit(cmd)--+      (this order IS the order of the world)
```

### The algorithm and its cost

The data structure is two `TreeMap`s (one per side) from price to a level. A level is a `LinkedHashMap` from order id to order: it iterates in arrival order (FIFO) and removes by id in O(1). One more map holds every order by id.

| Operation | Cost (L = price levels on the side, k = orders touched) |
|---|---|
| Rest a new order | O(log L) to find or create its level, O(1) to append |
| Sweep | O(k + emptied levels * log L) |
| Cancel | O(log L) level lookup + O(1) hash removal. Not O(1) overall, because of the sorted map. |
| FOK pre-check | O(k), stops early once enough is found, reads only |

Matching loop for an incoming order: take the best level on the other side. If the order has a price and it does not cross, stop. Otherwise walk the level front to back, trade `min(taker left, maker left)` at the MAKER's price, remove makers that reach 0, remove the level when empty. Stop when the taker is done or the other side is empty. Whatever is left rests (limit), or is cancelled (IOC, market).

Money maths: the *notional* is price times quantity (the money value of a trade). `Trade.notional()` is `Math.multiplyExact(price, qty)`. A new order whose price times quantity overflows a `long` is rejected up front, so later maths cannot wrap. The FOK pre-check and a replace's quantity growth are overflow-safe too, and the book is changed only after every check that can throw.

### The concurrency model

| Question | Answer |
|---|---|
| Shared state | The whole book, the trade list and the log. |
| Invariant | I1-I5 hold between any two commands, and the log order is the only order. |
| Primitive | None on the book: ONE engine thread owns it. Callers use a bounded `ArrayBlockingQueue` ([Topic 22](../22-blocking-queue-thread-pool/)) and a `CompletableFuture` for the answer. |
| Why | Matching is one long read-modify-write across many orders. Locking per order cannot make it atomic, and one global lock just makes callers queue on the lock instead of a queue. A single writer has no contention, no deadlock, and a total order you can replay. |
| Backpressure | The queue is bounded: `submit` waits when it is full. |
| Close | `close()` and `submit()` share one small lock (held while `submit` waits for room), so nothing can slip in behind the stop marker. Queued work finishes first. `close()` survives an interrupted caller. An `Error` on the engine thread fails every waiting future. `submit` from the engine thread itself is refused (it would deadlock). `book()` works only after close. |

### Code and tests

| File | Role |
|---|---|
| [`Command`](src/main/java/com/lld/matching/Command.java), `Side`, `OrderType`, [`Trade`](src/main/java/com/lld/matching/Trade.java), `Result`, `OrderView` | Inputs, outputs and the error contract. |
| [`Book`](src/main/java/com/lld/matching/Book.java), [`LimitOrderBook`](src/main/java/com/lld/matching/LimitOrderBook.java) | Interface, book, invariant checker. |
| [`MatchingEngine`](src/main/java/com/lld/matching/MatchingEngine.java) | F2: queue + engine thread. |
| `LimitOrderBookTest` (18) | README example, maker price both ways, FIFO, price beats time, partial fills, 18 bad commands write nothing, ids never reused, cancel/replace of filled or cancelled orders rejected, replace overflow rejected, near-MAX FOK fill and kill, invariants after every command of 4 random flows. |
| `OrderTypesTest` (8) | Market, IOC, FOK fill and kill, FOK limit price, snapshots unchanged after every killed FOK in a random flow. |
| `ReplayAndDifferentialTest` (10) | Cancel, replace rules and their `Result`, 8 seeded replays, replay test not vacuous (swapped commands change the trades), 12 seeds vs `NaiveBook` comparing the trades of every command. |
| `EngineTest` (10) | Future result, rejection, close and drain (thread really stopped), a non-IAE exception fails one future, an `Error` strands no future, close while interrupted, no submit from the engine thread, `book()` after close only, 8 producers behind a start gate vs sequential replay. |
| `NaiveBook`, `Flow` (test helpers) | Flat list scan reference matcher; seeded flow generator. `ThroughputBenchmarkTest` is the opt-in measurement ([commands.md](commands.md)). |

---

## 3. The Gaps

### F1: Market, IOC and FOK orders

Market: no price, so it never stops on price; it sweeps until filled or the side is empty, and the unfilled part is cancelled, never rested (a resting market order has no price to sit at). IOC: a limit price, trade what crosses, cancel the rest. FOK: first run a read-only pre-check `fillable()` that adds up the quantity at crossing prices; if it is less than the order, return KILLED and touch nothing. Only if it passes does the normal sweep run, and it must succeed fully. Tests: `fokThatCannotFillLeavesTheBookUnchanged` compares snapshots, and `randomKilledFoksNeverTouchTheBook` checks every kill in a 6000-command flow. Optional, not coded: *price protection* for market orders, i.e. a band (for example 5% from the last trade) beyond which a market order will not sweep, so one fat finger cannot walk through the whole book.

### F2: Single writer and its throughput

Why one thread beats locks is in the concurrency table above; this is the idea behind the LMAX exchange (see References, Fowler): keep the business logic on one thread, feed it from a queue, and replay the input log to recover. The test `concurrentProducersEqualSequentialReplayOfTheLog` starts 8 producers behind a latch, each submitting its own seeded flow. We cannot know the interleaving in advance, so the check is a differential: replay the engine's own accepted-command log sequentially into a fresh book and demand the same result for every command, the same trades and the same final book.

Measured (a measurement of this toy, not a benchmark): Apple M3 Max, OpenJDK 21.0.8, one seeded flow of 500,000 commands, 3 warm-up runs then 5 timed runs, median. A bare book on one thread: about 6.0 million commands/s. Through the queue and engine thread with one producer: about 3.3 million commands/s (the handoff costs about half). Full output in [commands.md](commands.md). The flow keeps the book shallow (11 prices, small quantities), so a real exchange with deep books and cache misses will be slower. Treat the numbers as "this design is not the bottleneck", nothing more.

### F3: Cancel, replace and event-log replay

Cancel removes the whole remainder. Replace follows the convention that a quantity DECREASE at the same price keeps the queue place, and a price change or a quantity increase goes to the BACK of the queue (it may trade at once if the new price crosses). This is the common exchange convention: Cboe BZX Rule 11.12(a) says a decrease in size keeps time priority and any other change loses it; Eurex lists a price-improving modify among the cases treated as incoming orders (see References). Venues differ in details. Our `Replace.newQty` is the quantity STILL WANTED; in FIX, the OrderQty of a Cancel/Replace (35=G) is the total intended quantity including what is already filled (also in References). That is a deliberate difference. 

Every accepted command is appended to the log, and a rejected one is not. `replayReproducesTheBookAndTheTrades` replays 8 seeded flows of 4000 commands into a fresh book and demands an equal snapshot, trades, orders and log. `matchesTheNaiveReference` runs 12 flows through the fast book and `NaiveBook` (one flat list, a full scan per match) and demands identical trades and final book.

### Misconceptions and common mistakes

| Mistake | Why wrong |
|---|---|
| Prices as `double` | 0.1 + 0.2 != 0.3, so equal prices land in different levels or cross wrongly. Use `long` ticks. |
| Trading at the incoming order's price | The maker posted a price and is promised at least that. A buy at 110 against an ask at 100 trades at 100 (`tradesAtTheRestingPriceNotTheIncomingPrice`). |
| A FOK that partially mutates | Filling first and undoing later leaves a window with a changed book and trades already published. Check first, then act. |
| Wall clock or hash order as priority | Two orders in the same millisecond tie, and `HashMap` iteration follows hash order, which is unspecified (not arrival order). Use the arrival stamp and a `LinkedHashMap`, so replay is identical. |
| Locking the book per order | Matching spans many orders, so per-order locks cannot protect it. One writer needs no lock on the book. |
| Keeping priority on a quantity increase | Lets a trader jump the queue by editing a big order. Only a decrease keeps its place. |
| Reusing an id after fill | A late duplicate message would create a new order. Ids stay used. |

### Trade-offs and when not to use

One thread caps throughput at one core per book; that is fine because a book is one instrument (shard by instrument). A `TreeMap` is O(log L); an array of levels indexed by tick is O(1) for dense price ranges but wastes memory on sparse ones. `LinkedHashMap` per level costs memory per order; an intrusive linked list would be leaner but needs more code. Not for: auctions or batch matching (see below), or pro-rata matching rules.

### Production and distributed delta

- One book per instrument, many books sharded across engine threads (hash the instrument to a thread). Orders for different instruments never touch each other, so the single-writer idea scales sideways.
- Journal the command log to disk before acting, and replicate it to a standby that replays it. Because the engine is deterministic, the standby ends in the same state. This is the LMAX recipe.
- Add: sequence numbers from the gateway, risk checks before the queue, market data and trade publication from the output events, snapshots to shorten replay.
- Related designs: [Topic 34: Auction](../34-auction/) matches in a batch at one price instead of continuously. Time rules are in [Topic 10](../10-time-and-testability/), money and idempotency in [Topic 11](../11-money-ids-idempotency/), threads and queues in [Java concurrency](../../internal-pocs/Java-poc/concurrency/).

---

## 4. Official References

- Martin Fowler, [The LMAX Architecture](https://martinfowler.com/articles/lmax.html): one business-logic thread, input queue, event sourcing and replay.
- LMAX, [Disruptor](https://lmax-exchange.github.io/disruptor/): the ring buffer that replaces the queue in the real thing.
- Wikipedia, [Order book](https://en.wikipedia.org/wiki/Order_book) and [Order matching system](https://en.wikipedia.org/wiki/Order_matching_system): vocabulary and the price-time rule (background, not a rulebook).
- Oracle Javadoc: [TreeMap](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/TreeMap.html), [LinkedHashMap](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/LinkedHashMap.html), [CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html).
- Cboe, [BZX Exchange Rulebook](https://cdn.cboe.com/resources/regulation/rule_book/BZX_Exchange_Rulebook.pdf): Rule 11.12(a): "Any other modification to an order, including an increase in the size of the order and/or price change, will result in such order losing time priority" (a decrease in size is among the listed exceptions that keep it). I downloaded the PDF and read that passage.
- Eurex, [Matching principles](https://www.eurex.com/ex-en/trade/order-book-trading/matching-principles): a price-improving modify is treated as an incoming order.
- Nasdaq, [TotalView-ITCH 5.0 specification](https://www.nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHspecification.pdf): its Order Replace message is a cancel-replace that removes the old order and gives the replacement a new order reference number.
- OnixS, [FIX 4.4 Order Cancel/Replace Request (G)](https://www.onixs.biz/fix-dictionary/4.4/msgType_G_71.html): OrderQty is the total intended quantity.

---

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Define bid, ask, cross, price-time. Ask: one instrument? order types? State `long` ticks. |
| 5-12 | Draw a small book on the board and walk one incoming order through it, trades at the maker's price. |
| 12-22 | Write `Book`, `Command` and the error contract. TreeMap of FIFO levels plus id index; state the costs. |
| 22-30 | Matching loop, partial fills, rest the remainder. Say the invariants out loud. |
| 30-36 | Market, IOC, FOK with a read-only pre-check. |
| 36-42 | Single writer, queue, futures; why not locks; replay from the command log. |
| 42-45 | Tests: invariants after every command, differential vs a naive matcher, replay. Production: shard by instrument, journal, replicate. |

---

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Numbers | `double` or `BigDecimal` prices | `long` ticks, `multiplyExact`, reject overflow up front |
| Matching | Sorted structure, FIFO | Maker price, level removal, invariants checked after every command |
| FOK | Fills then rolls back | Read-only pre-check, tested with snapshot equality |
| Concurrency | `synchronized` on the book | One writer, bounded queue, futures, deterministic order |
| Replay | "We could log it" | Command log is the source of truth, replay equals live (tested) |
| Testing | A few examples | Random flows, differential vs a naive matcher, mutation-checked |

---

## 7. Key Takeaways

1. Integers only: `long` ticks and quantities; overflow is rejected before anything changes.
2. Best price first, then arrival order; trades happen at the RESTING order's price.
3. Sorted map of FIFO levels plus an id index: rest and cancel in O(log L), sweep in O(k + levels * log L).
4. Market and IOC cancel their leftovers; FOK checks the whole order first and leaves the book untouched on reject.
5. One engine thread owns the book. A queue in, futures out: no lock on the book, no races, one order of events (`submit` holds one small lock only to keep "closed" and "enqueue" together).
6. Log every accepted command. Replaying it gives the same trades and book, which a seeded test proves.
7. Check an optimised book against a naive matcher, and shard by instrument in production: one book per instrument, journal plus replicas, and an auction ([Topic 34](../34-auction/)) for batch pricing.

**Self-grade: STRONG.** Full spine, v1 and F1-F3 coded, 46 tests plus an opt-in benchmark, mutation-checked (see commands.md). Gaps: I read only the quoted passages of the Cboe, Nasdaq and Eurex documents, not each in full; throughput is one machine on a shallow synthetic book; no self-trade prevention, price protection (described only), stop or iceberg orders, or snapshotting.

[← Topic 34: Auction](../34-auction/) · [Index](../README.md) · [Topic 36: Ride/Delivery Dispatch →](../36-ride-delivery-dispatch/)
