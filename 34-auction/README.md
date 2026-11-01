# Topic 34: Auction

"Build an auction." It looks like `if (bid > highest) highest = bid`. The interviewer is waiting for what breaks: the price a winner pays is not the bid they typed, a bid that arrives at the exact closing time must have one clear answer, a last-second bid can steal the item, and two bids at the same moment must never both be told "you lead".

A staff answer uses integer money, shows the proxy-bidding rule on a timeline first, closes at a half-open time from an injected clock, keeps every bid decision inside one lock per auction, and proves the race with a forced interleaving before showing the fix.

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

### Worked example first

Money is in cents. The *increment* is 100 (the smallest step a price may rise by) and there is no reserve. A *reserve* is the secret minimum price the seller accepts; it comes right after the table. A *proxy bid* is a secret maximum: you say "I will pay up to 1500" and the system bids for you, one increment at a time, only as high as needed to keep you in front. The visible price is what everyone sees right now. The test is `EnglishAuctionTest.workedProxyTrace`.

| Time | Bid (bidder, max) | Leader after | Visible price | Why |
|---|---|---|---|---|
| 1 | alice 1000 | alice | 100 | First bid: price is one increment, not her max. |
| 2 | bob 500 | alice | 600 | Bob is lower. Price = bob's max + 100, capped at alice's max. |
| 3 | carol 700 | alice | 800 | Still lower than 1000: 700 + 100. |
| 4 | bob 1000 | alice | 1000 | Equal max: the earlier bid (alice) keeps the lead. Price = that max. |
| 5 | carol 1500 | carol | 1100 | Carol is higher. Price = alice's max + 100. |
| 6 | carol 2000 | carol | 1100 | The leader raised her own max. The visible price does not move. |
| 7 | alice 1900 | carol | 2000 | Alice is lower: 1900 + 100 = 2000, capped at carol's max 2000. |
| close | none | carol | pays 2000 | She pays the visible price, which equals her max here only because alice pushed it there. |

Had alice stopped at 1000, carol would have paid 1100 for her 2000.

Reserve example (reserve 1000, increment 100, test `reserveLiftsThePriceAndRaisesTheMinimumBid`): alice bids 1200 and the price jumps to 1000 at once, because her max meets the reserve. Bob's 1050 is rejected (the minimum is now 1100). At the close alice pays 1000. While no max reaches the reserve the price stays below it and the item does not sell.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Money type? | `long` minor units. `Math.addExact` everywhere, overflow throws. |
| First bid rule? | `max >= increment`. The visible price becomes one increment. |
| Later bid rule? | A new bidder needs `max >= price + increment`. The leader can only raise their own max. |
| Close? | Half-open: open while `now < endsAt`. A bid at exactly `endsAt` is rejected. |
| Equal max? | The earlier bid wins. The price becomes that max. |
| Reserve? | Leader max below it: no sale. Once the leader's max meets it, the visible price is lifted to the reserve and the winner pays the visible price. |
| Who cannot bid? | The seller. Also nobody after the close. |
| Out of scope | Users and payment, buy-it-now, bid retraction, multiple items, real clocks, REST. |

The proxy rules are modelled on eBay's automatic bidding ([eBay Help: Bidding](https://www.ebay.com/help/buying/bidding/automatic-bidding?id=4014) returns 200 but serves a bot-check page to curl, so I could not read it). The tie rule, first-bid rule and reserve rule are assumed eBay behaviour, not verified.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Meaning |
|---|---|
| `Item` | id, seller, reserve, increment (a record that validates itself). |
| `AuctionState` | Immutable snapshot: leader, leader max, visible price, accepted count, `endsAt`, extensions, outcome. |
| `Auction` | The interface: `bid`, `settle`, `state`. |
| `Proxy` | The pure rules: old snapshot + bid in, new snapshot out (or an exception). Shared by the safe and the racy class. |
| `LockedAuction` / `RacyAuction` | The fixed class (one lock) and the deliberately broken one. |
| `SoftClose` | F1 setting: window and extension cap. |
| `SealedBidAuction` / `Vickrey` | F3: hidden bids and the second-price rule. |

- I1: the visible price is at most the leader's max (and, once the max meets the reserve, at least the reserve).
- I2: the visible price never goes down.
- I3: there is exactly one leader after the first accepted bid, and `seq` is 1..n with no gaps.
- I4: no bid is accepted at or after `endsAt`, and none after `settle`.
- I5: a rejected call writes nothing (every check and `addExact` runs before the new snapshot is built).
- I6: `settle` gives one outcome, and the same object on every repeat call.

### The interface and its error contract

```java
public interface Auction {
    BidReceipt bid(String bidder, long max);
    // IllegalArgumentException: blank bidder, max <= 0.
    // BidRejectedException(reason): CLOSED (now >= endsAt or already settled), SELLER_BID, TOO_LOW (first: max < increment; later: max < price + increment, price already lifted to the reserve), NOT_RAISING (leader sends max <= own max).
    // Prices never overflow: near Long.MAX_VALUE they cap at the leader's max, and when price + increment no longer fits every bid is TOO_LOW. In every failure nothing is written.
    Outcome settle();   // IllegalStateException if now < endsAt. Repeat calls return the same Outcome.
    AuctionState state();
}
```

`BidRejectedException` carries a `Reason` enum, so a caller (or a REST layer later) can map it to a status without parsing text.

### Class and sequence diagrams

```
 Auction <|-- LockedAuction (ReentrantLock) --> AuctionState (immutable snapshot, swapped whole)
         <|-- RacyAuction   (no lock, Gap hook)     |
              both call ---> Proxy.apply(item, soft, now, state, bidder, max)
 SealedBidAuction --> Vickrey.settle(bids, reserve)        SoftClose(window, maxExtensions)

 bid("bob", 500)
   caller --> LockedAuction : lock
   LockedAuction : now = clock.instant()   (read INSIDE the lock)
   LockedAuction --> Proxy.apply : validate, compute next snapshot   (throws? nothing written)
   LockedAuction : state = next ; unlock
   caller <-- BidReceipt(seq, leading, leader, price, endsAt)
```

### The algorithm and its cost

The rules, using the trace above. Let `L` be the leader max, `p` the price, `i` the increment, `m` the new bid from someone else.

| Case | Leader after | Price after |
|---|---|---|
| No leader yet | the bidder | `i` |
| `m < p + i` | unchanged | rejected, TOO_LOW |
| `m > L` | the bidder | `min(m, L + i)` |
| `m <= L` | unchanged (a tie keeps the earlier bid) | `min(L, m + i)`, which is `L` on a tie |
| Leader raises own max | unchanged | unchanged (`p`) |

Each bid is a handful of comparisons: O(1) time and O(1) space, because the snapshot only remembers the leader, not every bid. `min(L, m + i)` is computed as `m >= L - i ? L : m + i`, so it never overflows. After the table, if `L >= reserve` the price is lifted to `max(p, reserve)`, and the winner pays the visible price. If `L < reserve` there is no sale. Tests: `reserveNotMetMeansNoSale`, `leaderMaxEqualToReserveSells`, the three `NearOverflow` tests, and a 300-round seeded test (`matchesNaiveModelAndPriceNeverDecreases`) against a naive model that checks I1 and I2 on every bid.

The close is half-open: a bid 1 ns (or 1 ms) before `endsAt` is accepted and one at exactly `endsAt` is rejected (`closeIsHalfOpenAtNanosecond`, `closeBoundaryAtMillisecond`). A settled auction stays closed even if the clock steps back (`settledAuctionStaysClosedWhenTheClockStepsBack`). Time comes from an injected `Clock` ([Topic 10](../10-time-and-testability/)); the tests use a hand-moved clock.

### The concurrency model

| Question | Answer |
|---|---|
| Shared state | One `AuctionState` reference per auction (leader, price, end time, outcome). |
| Invariant | I1-I6 hold every time the lock is released. |
| Primitive | One `ReentrantLock` per auction. The clock is read inside the lock. |
| Why | The critical section is a few comparisons, the caller wants the receipt now, and auctions never touch each other, so no lock ordering is needed (compare [Topic 08](../08-multi-object-atomicity/), where two objects had to be locked together). |
| Alternative | A single-writer queue per auction: one thread owns the state and bids are messages. It costs a hand-off per bid but gives a replayable log and suits a distributed owner (my judgement, not measured). See [Topic 35](../35-order-matching-engine/). |
| Why the clock is inside | A bid that read the clock just before `endsAt` and then waited for the lock could be accepted at or after `endsAt`, breaking I4 (with soft close it would also extend from a stale stamp). Reading inside gives "bid or close first" one answer. Test: `clockIsReadInsideTheLockSoAWaitingBidSeesTheEnd`. |

### Code and tests

| File | Role |
|---|---|
| [`Auction`](src/main/java/com/lld/auction/Auction.java), [`BidRejectedException`](src/main/java/com/lld/auction/BidRejectedException.java) | Interface, rules and error contract. |
| [`Item`](src/main/java/com/lld/auction/Item.java), [`AuctionState`](src/main/java/com/lld/auction/AuctionState.java), [`BidReceipt`](src/main/java/com/lld/auction/BidReceipt.java), [`Outcome`](src/main/java/com/lld/auction/Outcome.java), [`SoftClose`](src/main/java/com/lld/auction/SoftClose.java) | Immutable records and the F1 setting. |
| [`Proxy`](src/main/java/com/lld/auction/Proxy.java) | Pure proxy-bidding and reserve rules, validate-then-build. |
| [`LockedAuction`](src/main/java/com/lld/auction/LockedAuction.java), [`RacyAuction`](src/main/java/com/lld/auction/RacyAuction.java), [`Gap`](src/main/java/com/lld/auction/Gap.java) | The fix, the bug, and the test hook. |
| [`SealedBidAuction`](src/main/java/com/lld/auction/SealedBidAuction.java), [`Vickrey`](src/main/java/com/lld/auction/Vickrey.java), [`SealedBid`](src/main/java/com/lld/auction/SealedBid.java) | F3. |
| `EnglishAuctionTest` (19), `SoftCloseTest` (5), `ConcurrencyTest` (7), `SealedBidTest` (12) | 43 tests: rules and boundaries, reserve and overflow, soft close, forced interleavings and hammer, Vickrey and the truthful-bidding property. |

---

## 3. The Gaps

### F1: Anti-sniping (soft close)

*Sniping* is bidding in the last second so nobody can answer. A *soft close* says: a bid accepted in the last `W` before the end moves the end to `end + W`. The window includes its first instant (`end - W`), and a bid exactly at the end is still rejected.

Example (end 60 s, W 10 s): alice at 50 moves the end to 70; bob at 60 moves it to 80; alice at 79 moves it to 90; bob at 90 is rejected.

Test: `chainOfExtensions`. The extension counts from the old end (`end + W`), so the time left after a late bid is between W and 2W. The alternative is `now + W`, which always leaves exactly W. Cap: `SoftClose.maxExtensions`; `unlimited` has no cap, so the auction ends when people stop bidding. The risk is two stubborn bidders extending forever, so a production system may set a cap or a hard final deadline. With a cap, the capped bid is still accepted but no longer moves the end (`capStopsExtensions`).

Why soft close helps the seller (my reasoning, not a published result; see [Auction sniping](https://en.wikipedia.org/wiki/Auction_sniping)). Alice values the item at 1000 and bids 700 early. Bob values it at only 900 and snipes 800 at second 55. With a hard close bob wins at 800. With soft close alice sees the snipe, answers 1000 at second 62 and wins at 900 (bob's max 800 plus 100). The highest-value bidder wins and the seller gets more. Test: `softCloseLetsTheHigherValueBidderAnswerASnipe`.

### F2: Concurrent bids

Choice: one lock per auction (reasons in the table above). The defect is shown, not just described.

Forced interleaving: alice's bid is parked in the `Gap` after it read the empty state and before it wrote. Bob's bid runs now.

| Step | `RacyAuction` | `LockedAuction` |
|---|---|---|
| alice reads the empty state, computes "I lead, seq 1", parks | | takes the lock, parks inside it |
| bob bids 500 | reads the same empty state, computes "I lead, seq 1", writes it | blocks on the lock (`awaitWaiting` sees it queued) |
| alice resumes | writes her state over bob's | finishes, unlocks |
| bob | | runs on alice's state: seq 2, not leading, price 600 |
| Result | two receipts say `leading=true` with `seq=1`, `accepted=1`, bob's bid is gone | one leader, seq 1 and 2, price 600 |

Tests: `racyAuctionAcknowledgesTwoLeadersAndLosesABid` proves the defect, `lockedAuctionSerialisesTheSameInterleaving` passes the same interleaving. The same shape for the close: a late bid passed the "still open" check, the close announced alice, then the bid overwrote the state, so `settle()` returns two different outcomes (`racyCloseAndLateBidGiveTwoOutcomes`). With the lock the close waits for the bid and announces bob once (`lockedCloseAndLateBidGiveOneOutcome`).

Hammer (`hammerMatchesSequentialReplay`): 8 threads released by a start gate place 250 bids each. Receipts have `seq` 1..n exactly once, and replaying the accepted bids one by one in `seq` order gives the identical final state. No sleeps anywhere: parked threads wait on latches.

### F3: Sealed-bid second price (Vickrey)

Each bidder sends one hidden bid. After the end the highest bid wins and pays the second-highest bid (or the reserve, if higher; with only one bid that is the reserve). Ties: the earlier bid wins and pays the tied amount.

Example: amy 800, zed 900, bob 700 with reserve 500: zed wins and pays 800.

Why telling the truth is best: your bid decides only whether you win, never what you pay. Bid below your value and you may lose an item you would have profited from; bid above and you may win and pay more than it is worth. `biddingTrueValueIsNeverWorseThanShading` samples 3000 seeded rounds against random rivals (reserve, 0 to 4 rivals, first and last arrival) and 20 other bids each. This is the result of Vickrey (1961), see References.

Proxy bidding is the same idea run live: you give a max and you pay roughly the runner-up's max plus one increment. When every max is accepted, English pays `min(top, second + i)` and Vickrey pays `second`, a difference of at most one increment. They can differ by less when a bid is rejected: with increment 100, maxes 1000 and 500, and 650 rejected as too low, English ends at 600 and Vickrey at 650. `proxyBiddingMatchesSecondPrice` uses increment 1 and places distinct maxes in rising order, so every one is accepted, and checks English = Vickrey + 1.

### Misconceptions and common mistakes

| Mistake | Why wrong |
|---|---|
| "The highest bidder pays their own max" | Under proxy bidding the winner pays about the runner-up's max plus an increment. Carol above could have paid 1100 for her 2000. |
| A closing job that sets `closed=true` while bids are processed | A bid can pass the open check, the job announces, then the bid overwrites it (the racy test). Decide and read the clock in the same lock. |
| A hard close "for fairness" | Rewards the sniper, not the highest value. See F1. |
| Tie goes to the later bid or a hash order | Not deterministic. The earlier bid wins, and it is documented. |
| `double` money, unchecked `price + increment` | Rounding and silent overflow to a negative price. Use `long` and overflow-free arithmetic. |

### Trade-offs and when not to use

The snapshot design stores only the leader, so it cannot show a bid history or support retraction. Add an append-only bid list if you need either. One lock is perfect for one auction; for one auction with a very high bid rate a queue with batching may be better (my judgement, not measured). Soft close makes the end time unpredictable for sellers who need a schedule.

### Production and distributed delta

- A distributed auction needs one owner per auction. Hash the auction id to a node, or use a per-auction single writer (an actor). All bids for it go to that owner, so the lock becomes "the mailbox". [Topic 35](../35-order-matching-engine/) builds exactly this single-writer design for an order book.
- Store an event log (BidAccepted, EndExtended, Settled) and derive the state from it, so a crash can replay. Idempotency keys on each bid stop a retried request from placing two bids ([Topic 11](../11-money-ids-idempotency/)).
- The close is a scheduled event ([Topic 23](../23-delayed-job-scheduler/)), but the decision must still be made by the owner, not by the scheduler thread, for the same race reason as above.
- Locks and executors in depth: [Java concurrency](../../internal-pocs/Java-poc/concurrency/).

---

## 4. Official References

- William Vickrey, "Counterspeculation, Auctions, and Competitive Sealed Tenders", *The Journal of Finance* 16(1), 1961, pp. 8-37: [JSTOR 2977633](https://www.jstor.org/stable/2977633) (200). The DOI link returned 403 to curl, so I cite the JSTOR page. I did not read the paper, only know its result via the sources below.
- Wikipedia: [Vickrey auction](https://en.wikipedia.org/wiki/Vickrey_auction) (the second-price rule and truth-telling), [English auction](https://en.wikipedia.org/wiki/English_auction), [Auction sniping](https://en.wikipedia.org/wiki/Auction_sniping) (all 200).
- eBay Help: [Bidding](https://www.ebay.com/help/buying/bidding/automatic-bidding?id=4014) (200, but a bot-check page to curl, so our rules are assumed eBay behaviour).
- Oracle, [ReentrantLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html) (200) and [Clock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html) (200).
- Book: Tim Roughgarden, *Twenty Lectures on Algorithmic Game Theory*, chapter 2 (Vickrey auctions, "Mechanism Design Basics"). Not opened for this topic.

---

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-12 | Clarify (English or sealed, reserve, increment, proxy, ties, the exact end, `long` cents); draw the timeline table showing the winner pays less than their max. |
| 12-30 | `Auction` and its error contract, the `Proxy` rule table, half-open close, validate before writing, reserve and settle. |
| 30-42 | Concurrency: the two races (two bids; close vs bid), one lock with the clock inside; soft close and its cap; mention sealed second price. |
| 42-45 | Tests: `Gap` forced interleaving, hammer vs sequential replay; production owner-per-auction. |

---

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Price and time | Highest bid is the price; `<=` on `new Date()` | Proxy price (second max + increment, tie rule), injected `Clock`, half-open close tested on the exact nanosecond |
| Concurrency | `synchronized` on the method | Names the two races (two bids; close vs bid), clock read inside the lock, forced-interleaving test for the bug |
| Failure | Mutates then validates | Validate first, `addExact`, a rejected call writes nothing, typed reason |
| Fairness and theory | Hard close | Soft close with a cap and a labelled argument; knows Vickrey truthfulness and proxy bidding are the same idea |
| Production | "Use a database" | One owner per auction, event log, idempotency, scheduler only proposes the close |

---

## 7. Key Takeaways

1. The visible price is the runner-up's max plus an increment, never the leader's max; the winner pays at most their own max.
2. Close is half-open and tested on the exact nanosecond; a settled auction stays closed.
3. Decide everything under one lock with the clock read inside it, so "close vs late bid" has one outcome. Prove the race with a `Gap` hook first.
4. Soft close extends only on accepted bids and needs a cap or a stated reason for none.
5. Sealed second price makes truth-telling optimal; proxy bidding is that idea run live. Scale by giving each auction one owner.

**Self-grade: STRONG.** Full spine, v1, F1, F2 and F3 coded with 43 tests; six surviving mutations from review are each killed by a named test (table in `commands.md`). Gaps: eBay's rules were unreadable (bot-check page), so they are assumed; the Vickrey paper was not read; the soft-close benefit is reasoning; one lock per auction, no event log.

[← Topic 33: Splitwise](../33-splitwise/) · [Index](../README.md) · [Topic 35: Order-Matching Engine →](../35-order-matching-engine/)
