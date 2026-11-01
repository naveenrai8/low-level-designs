# Topic 36: Ride/Delivery Dispatch

"A rider taps Request. Find a driver." The easy answer is to loop over every driver and take the closest. The interviewer is waiting for the questions that break it: 1) how do you avoid scanning a million drivers, 2) is the nearest driver always the right one, 3) what if the driver never answers, 4) what if two dispatchers pick the same driver, 5) what happens to the price when demand spikes? A staff answer starts from a small grid picture, proves the ring search stops at the right moment, then adds batching, a driver state machine with timeouts, and a surge price locked at request time.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

Words defined on first use: a **grid index** cuts the map into equal squares and keeps a list of drivers per square, so a search only looks at squares near the rider. A **cell** is one square, named by a column and a row number. A **ring** is the set of cells exactly r steps from the rider's cell (ring 0 is the cell itself, ring 1 is the 8 cells around it, ring 2 the 16 around those). A **batching window** is a short wait (say 5 seconds) in which requests are collected and then assigned together. **Surge** is a price multiplier that rises when riders outnumber drivers. The **Hungarian method** is a classic algorithm that finds the cheapest way to pair rows with columns of a cost table. **p95** is the value that 95 percent of cases do not exceed.

## 1. The Problem

### Worked example first: the ring search on a tiny grid

Cells are 10 m wide. Rider R stands at (9,5). Driver A is at (0,5), in the same cell as the rider, 9 m west. Driver B is at (10,5), in the next cell, 1 m east. Columns are cell numbers, the top row shows x ranges.

```
 x:    0..9      10..19     20..29
      +--------+--------+--------+
 y    |  A     |  B     |        |    cell (0,0) holds A at x=0 and the rider R at x=9
 0..9 |      R |        |        |    cell (1,0) holds B at x=10
      +--------+--------+--------+
        cell     cell
        (0,0)    (1,0)
```

R is at x=9, so the nearest cell edge is 1 m away (the edge at x=10). Call that edge distance m = 1 (m is always between 1 and half the cell size, rounded up). Any driver outside rings 0 to r is at least r*10 + m metres away.

| Step | What we do | What we find | Best so far | Can anything unseen beat it? |
|---|---|---|---|---|
| 1 | scan ring 0 (cell (0,0)) | A at 9 m (squared 81) | A, 81 | unseen drivers are in ring 1 or farther, at least 0*10 + 1 = 1 m away, squared 1. 81 < 1 is false, so yes they could. Continue. |
| 2 | scan ring 1 (8 cells) | B at 1 m (squared 1) | B, 1 | unseen are in ring 2 or farther, at least 1*10 + 1 = 11 m, squared 121. 1 < 121 is true, so no. Stop. |

Answer: B, 1 m away.

### Why stopping at the first non-empty ring is WRONG

After step 1 we have a driver (A). The wrong rule stops there and returns A at 9 m, while B is 1 m away on the other side of a cell edge. A cell is a bookkeeping square, not a distance guarantee: "same cell" does not mean "nearer". Test `stoppingAtTheFirstNonEmptyRingPicksTheWrongDriver` runs exactly this world on the deliberately wrong `StopRule.FIRST_NON_EMPTY_RING` and shows it returns A (squared 81). `wrongRuleIsCaughtByTheDifferentialCheck` shows the wrong rule disagrees with brute force in dozens of random worlds, which is why the differential test catches it.

### What is really being probed

Spatial indexing basics (and where they stop being right), exact integer geometry, a correct stopping rule, assignment as an optimisation problem (not just "nearest"), a state machine with timeouts, a race on one driver, and money with a locked quote.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Coordinates? | `long` whole metres on a flat plane, each within plus or minus 1,000,000,000. Micro-degrees also work (1e-6 degree is about 0.11 m at the equator) as a flat-earth approximation. |
| Limits of flat earth? | Fine for one city (tens of km). Wrong near the poles, across the date line, or over hundreds of km, because a degree of longitude shrinks with latitude. Use H3 or S2 there. |
| Distance? | Squared `long` distance. No square root, no double, `multiplyExact` and `addExact` so an overflow throws instead of wrapping. The coordinate bound keeps the largest squared distance at 8e18, below the long maximum of 9.22e18. |
| Ties? | Equal distance goes to the smaller driver id, always, so a result never depends on insertion order. |
| Cell size? | A parameter, 1 to 1,000,000 m. Rule of thumb: about the distance you expect to find the nearest driver at. |
| Who is searched? | Only AVAILABLE drivers. A driver with an offer, on the way or on a trip is out of the grid. |

Out of scope: road networks and real ETAs (a straight line is a proxy), driver incentives, multi-stop pooling, payments (see [Topic 32](../32-double-entry-ledger/README.md)), persistence, and maps.

## 2. How It Solves It

### Entities and invariants

| Entity | Fields | Invariant |
|---|---|---|
| `Point`, `Cell` | x, y metres; column, row | coordinates within the bound; cell by `floorDiv` |
| `GridIndex` | cell to sorted set of driver ids, driver to point | a driver is in exactly one cell; empty cells are dropped |
| `Fleet` | state, info, live offers per driver | a driver is in the grid exactly when AVAILABLE; at most one live offer per driver |
| `Offer` | id, driver, request, deadline | accepted only while the clock is below the deadline; ids are never reused |
| `Dispatcher` | requests, declined sets, timers | a request is assigned to at most one driver; a decliner is never offered the same request again |
| `Quote` | request, surge bps, price | fixed at submit time |

### The interface and the error contract

`DriverIndex` is the interface (`upsert`, `remove`, `nearest(point, filter)`, `countInCellOf`, `cellOf`, `size`). `LinearIndex` (scan everything) and `GridIndex` implement it. Errors are `DispatchException` with a `Code`:

| Code | When |
|---|---|
| `OUT_OF_RANGE` | coordinate beyond the bound, cell size outside 1..1,000,000, negative window or fare, batch over 256 |
| `UNKNOWN_DRIVER`, `UNKNOWN_REQUEST`, `DUPLICATE_REQUEST` | ids the dispatcher does not know, or reuses |
| `ILLEGAL_TRANSITION` | a driver move not in the table |
| `STALE_OFFER` | accept or reject of an offer that timed out, was answered, or never existed |
| `BAD_COST` | a `ScoringStrategy` returned a negative cost or one above 2^32 |

Every check runs before any write, so a refused call changes nothing (tests assert the state afterwards).

### Class diagram

```
 Dispatcher --uses--> ScoringStrategy (DISTANCE | distanceAndRating | ETA)
     |  \--uses--> BatchAssigner (Greedy | Optimal)    \--uses--> PricingPolicy (RatioSurge)
     |  \--uses--> Scheduler (ManualScheduler = virtual time)
     v
   Fleet ---- DriverState table, live offers, Gap hook
     |
     v
 DriverIndex <|-- GridIndex (rings)   DriverIndex <|-- LinearIndex (brute force reference)
```

### Sequence: request, offer, accept, timeout, re-dispatch

```
 rider      Dispatcher        GridIndex        Fleet        Scheduler       driver
   |--request-->| quote locked    |               |             |              |
   |            |--(window)-------|---------------|--timer----->|              |
   |            |--nearest(excl. decliners)------>|             |              |
   |            |--tryOffer(d)--------------------->| AVAIL->OFFERED, off grid  |
   |            |--schedule(timeout)--------------------------->|--offer------>|
   |            |<------------------------------ accept(offerId)-----------------|  live? yes -> EN_ROUTE
   |            |                                  or timer fires (no answer):
   |            |<--expire(offerId)---------------| OFFERED->AVAIL, back in grid
   |            | add d to declined; request PENDING; next flush picks the next best
```

### The algorithm and its cost

Update: `upsert` removes the driver from its old cell only if the cell changed, then adds it to the new one (O(log k) for a sorted set of k drivers in a cell); empty cells are dropped. Query: scan ring 0, 1, 2, ... and stop when the best squared distance is strictly below `(r*size + m)^2`, where m is how far the rider is from just outside its own cell on the nearest side (1 to ceil(size/2), from `floorMod`). The bound is tight: the test `lowerBoundIsSafeAndTightForEveryOffset` checks every integer point for sizes 1 to 4. Ring r has 8r cells, so a dense city (a driver or more per cell) needs 1 to 2 rings, about 9 to 25 lookups, O(1); a sparse area needs about sqrt(1/rho) rings for rho drivers per cell; and once 8r exceeds the number of occupied cells the search just visits each occupied cell, which guarantees it ends.

Choosing the cell size: too small and you scan many empty rings; too big and each cell holds a long list. When density varies a lot (city centre vs suburbs) a uniform grid is wrong in one of the two places: use a **quadtree** (cells split where crowded), **geohash** (a string prefix per cell, easy to store in a sorted key-value store), or **H3/S2** (hexagons or sphere-aware cells, no flat-earth error, better neighbour maths). Distributed delta: [SystemDesign Topic 84, Geospatial Indexing](../../pocs/SystemDesign-poc/84-geospatial-indexing/README.md).

### The concurrency model

- **Shared state:** the driver's state, the grid, and the live offers, all in `Fleet`. The dispatcher's request table is its own.
- **Invariant:** one driver, at most one live offer; an offer is answered at most once.
- **Primitive:** every `Fleet` mutator is `synchronized`, so "is the driver AVAILABLE?" and "make it OFFERED" are one step. Lock order is dispatcher then fleet, never the reverse. Several dispatchers can share one fleet.
- **Why not check outside the lock:** two dispatchers both see "AVAILABLE" and both offer. The `Gap` hook plus `Fleet.Mode.RACY_FOR_TESTS` shows this on demand. Background: [synchronized and monitors](../../internal-pocs/Java-poc/concurrency/12-synchronized-and-monitors/) and [race conditions](../../internal-pocs/Java-poc/concurrency/05-race-conditions/).
- **Time:** the `Scheduler` seam. Production would wrap a real delayed executor; tests use `ManualScheduler` (virtual time, as in [Topic 10](../10-time-and-testability/README.md) and the timers of [Topic 23](../23-delayed-job-scheduler/README.md)). No sleeps anywhere.

### Code and tests (66 tests, about 1 s of test time and 3 s wall with Maven, JDK 21.0.8, Apple Silicon, 3 runs)

Main classes: geometry (`Point`, `Cell`, `Geometry`), index (`DriverIndex`, `GridIndex`, `LinearIndex`, `StopRule`), F1 (`ScoringStrategy`, `GreedyAssigner`, `OptimalAssigner`, `BatchMatcher`, `Simulation`), F2 (`DriverState`, `Fleet`, `Offer`, `Dispatcher`, `Scheduler`, `ManualScheduler`, `Gap`), F3 (`PricingPolicy`, `RatioSurgePolicy`, `Quote`). `Fleet`'s offer methods are package-private, so `Dispatcher` is the only public way to answer an offer.

| Test file | What it shows |
|---|---|
| `GridIndexTest` (18) | worked example, wrong-rule defect, 4000 differential worlds (24,000 queries), exhaustive and tight stop bound, tie at the bound, probe counters (early stop, empty cells dropped), negatives, overflow |
| `BatchTest` (17) | greedy gap, Hungarian vs brute force (square and rectangular with forbidden pairs), decliner never reused, candidate cap, window, strategies, `BAD_COST` leaves nothing behind, pinned sim table |
| `DriverFlowTest` (24) | 25 state pairs against a hand-written list, every Fleet move from every state, timeout and early-timer re-arm, stale accept, foreign offer, cancel, expiry, racy defect and fix |
| `SurgeTest` (7) | monotone, capped, rounding, locked quote |

## 3. The Gaps

### F1: batching window and scoring Strategy (coded)

Immediate greedy gives each request the nearest driver the moment it arrives. That can waste a driver. Tiny example on a line: drivers D1 at x=5 and D2 at x=-20. R1 at 0 arrives first, R2 at 6 arrives 100 ms later.

| Policy | R1 gets | R2 gets | Total pickup distance |
|---|---|---|---|
| immediate greedy | D1 (5 m, nearest) | D2 (26 m, D1 is gone) | 31 m |
| batch, window 500 ms | D2 (20 m) | D1 (1 m) | 21 m |

Test `immediateGreedyCanBeWorseThanABatch` runs both. Inside a batch we need an assignment: a table of costs, one row per request and one column per driver. **Greedy by best global pair** repeatedly takes the cheapest pair left; it is fast but can be wrong: costs `[[4,6],[6,16]]` give 4 + 16 = 20 greedy, but 6 + 6 = 12 optimal. `OptimalAssigner` is the Hungarian method (O(n^2 m), exact), checked against brute-force permutations over 2000 square tables and 3000 rectangular tables with forbidden pairs; greedy was strictly worse in over 100 of the square ones. `ScoringStrategy` is the pluggable cost: `DISTANCE`, `distanceAndRating(metresPerTenthStar)`, `ETA` (distance over speed). The matcher asks the grid for each request's nearest allowed drivers (at most 8 each, and never more than the batch size) and scores only those; with `DISTANCE` and batches of 8 or fewer this is exact (checked against brute force), above that or with rating or ETA scoring it is "good, not provably optimal". Candidates are gathered under the fleet lock, then scored and assigned outside it.

**Measured simulation.** Setup: seeds 1 to 10 averaged, a 10 km by 10 km square, 300 requests arriving uniformly over 300 virtual seconds, drivers start at random places, an assigned driver accepts at once, drives the trip time, then reappears at a random place. Window W = 5 s for the batched policies, `DISTANCE` scoring. Immediate dispatch re-matches the moment a driver frees up; batched dispatch waits for the next window (`dispatchPending` bypasses the window, so the simulation only calls it when W = 0). Each cell is mean / p95 pickup distance in metres, then mean / p95 rider wait (request to offer, 1 s resolution). JDK 21.0.8, Apple Silicon. `BatchTest.simulationTable` pins these exact strings, so the docs cannot drift.

| Load (trips the fleet can do in 300 s vs 300 requests) | immediate greedy | batched greedy | batched optimal |
|---|---|---|---|
| light: 100 drivers, 30 s trips (capacity 1000) | 715 / 1629 m, wait 0 / 0 s | 711 / 1662 m, wait 3 / 5 s | 710 / 1638 m, wait 3 / 5 s |
| balanced: 60 drivers, 60 s trips (capacity 300) | 2684 / 6982 m, wait 3 / 15 s | 2097 / 6081 m, wait 7 / 26 s | 2004 / 5054 m, wait 7 / 25 s |
| overloaded: 40 drivers, 60 s trips (capacity 200) | 1311 / 4050 m, wait 61 / 213 s | 1221 / 3749 m, wait 66 / 219 s | 1212 / 3718 m, wait 66 / 219 s |

What it says, and a correction. My first run used three overloaded setups and gave "batching helps 1 to 5 percent, optimal adds nothing"; that was a load artefact, so I re-measured across loads. Light load: drivers are everywhere, so batching changes nothing (under 1 percent) and only adds about 3 s of waiting: do not batch. Balanced load, where drivers are scarce but not hopeless: batching cuts mean pickup distance by 22 percent and optimal by 25 percent (2684 to 2004), and p95 by 27 percent, at the cost of about 4 s more mean wait; here greedy-in-a-batch is also clearly worse than optimal (p95 6081 vs 5054). Overloaded: requests queue for minutes, the wait is dominated by the queue (61 vs 66 s), and batching gains about 7 percent of distance. So the window is a real trade: it pays only when drivers are scarce enough that who gets whom matters, and it always costs waiting time. The simulation ignores roads, accept rates and cancellations, so read the shape, not the numbers.

### F2: driver state machine and timeouts (coded)

```
 OFFLINE -> AVAILABLE -> OFFERED -> EN_ROUTE -> ON_TRIP -> AVAILABLE
               ^  |         |
               |  v         | reject or timeout
            OFFLINE         v
                        AVAILABLE (request re-dispatched, decliner excluded)
```

`DriverState.targets()` is the table; there are 7 legal moves and 18 illegal pairs out of 25 (including "move to the same state"). `everyStatePairMatchesTheHandWrittenLegalList` compares all 25 pairs with a hand-written list (not read back from the table), and `fleetApiRefusesEveryIllegalMoveFromEveryState` drives the real `Fleet` calls from every state and checks that a refusal changes nothing and the driver is in the grid exactly when AVAILABLE. State design background: [Topic 06](../06-state-machines/README.md). The offer timer is scheduled on the `Scheduler`; when it fires, `Fleet.expire` frees the driver only if the offer is still live, so a late timer never undoes an accept (`acceptCancelsTheTimeout`). A declined request goes back to PENDING with the driver in its declined set; the next flush picks the next best. If the fleet's clock says an offer still has time left when its timer fires (two clocks that disagree), the dispatcher re-arms the timer for the rest (`timerThatFiresEarlyIsRearmed`). A dispatcher refuses an offer id it did not issue before touching the fleet (`oneDispatcherCannotAnswerAnotherOnesOffer`). A request ends as ASSIGNED, CANCELLED (`cancel`) or EXPIRED (no driver within the maximum wait), and finished requests leave the active table so scans stay small.

**Stale offers.** Every offer has a never-reused id and a deadline. Accept checks "the offer is in the live table and now is strictly below the deadline". So accept-after-timeout is refused even in the instant before the timer task runs (`acceptJustBeforeTheDeadlineWorksAndAtTheDeadlineIsStale`), and an old id cannot answer a new offer to the same driver (`oldOfferIdCannotAnswerANewOffer`).

**The race.** Two dispatchers pick the same driver. In `RACY_FOR_TESTS` the check, the `Gap` hook and the write are not atomic: the hook lets a rival offer in between and one driver ends with 2 live offers (`racyOfferGivesOneDriverTwoOffers`). The fix is the same code under the fleet lock; `safeOfferLetsExactlyOneWin` runs the same interleaving, proves the rival thread is BLOCKED on the lock, and exactly one offer exists. `twoDispatchersOneDriverOnlyOneWins` does it at dispatcher level: the loser's request stays PENDING.

### F3: surge pricing seam (coded, small)

`PricingPolicy.surgeBps(openRequests, availableDrivers)` returns basis points (bps; 10,000 = 1.00x). `RatioSurgePolicy(slope, cap)`: no surge while drivers cover requests, else `min(cap, 10,000 + (open - available) * slope / max(available, 1))`. Example with slope 5000, cap 25,000: 6 open, 4 drivers gives 10,000 + 2*5000/4 = 12,500 (1.25x). Price = `base * bps / 10,000`, rounded half up, in `long` minor units (999 at 1.25x is 1248.75, so 1249). Tests: monotone (more demand never lowers it, more drivers never raises it) over a whole grid of counts, capped at any demand, and **locked**: `Quote` is stored at submit time, so later requests raising the surge to 2.0x leave the first quote unchanged. Open requests and drivers are counted per cell.

Design delta (not coded): raw ratio flaps, because one driver moving a cell edge changes the price. Smooth it with an exponential moving average of the ratio per cell over the last few minutes, add **hysteresis** (rise above 1.4x only after the ratio passes 1.5, fall back only below 1.3, so the price does not flicker at the threshold), limit the change per step (for example 0.2x per minute), and blend neighbouring cells so the price has no cliff at a cell border. A quote should also carry an expiry, so a rider who waits ten minutes re-quotes.

### Misconceptions and common mistakes

- **"Stop at the first non-empty ring."** Wrong, see the worked example. A driver 1 m away across an edge loses to one 9 m away inside the cell.
- **"Compare distances with doubles, equality included."** A `double` square root makes two equal distances differ by rounding, so the tie-break silently stops working and results change between runs. Compare squared `long` values.
- **"Cell = x / size."** In Java `/` truncates toward zero, so -1 / 10 = 0 and the cell around the origin is twice as wide. Use `Math.floorDiv` (and `floorMod` for the offset). `negativeCoordinatesUseFloorDivision` and the differential test (negative worlds) catch it.
- **"The timeout and the accept can't collide."** They can; decide by the deadline on the live-offer table under one lock.
- **"Greedy is optimal."** It is not (20 vs 12 above, and 6081 vs 5054 p95 in the balanced sim); even optimal per batch is not optimal over time.

### Trade-offs and when not to

A uniform grid is the right first answer for evenly spread supply and a city-sized map. Skip batching for delivery with sparse, slow-changing demand (the wait costs more than it saves). Skip the Hungarian method above about a few hundred requests per batch (use greedy, or a min-cost-flow library). Skip the straight-line distance when a river or highway makes it useless: use a road ETA service for the final score and keep the grid as the cheap candidate filter.

### Production and distributed delta

- **Shard by cell:** route all events for a region (a block of cells, or an H3 parent cell) to one owner, so the index and the "one offer per driver" check stay in one process. Cross-boundary searches query the neighbouring shards and merge. See [SystemDesign Topic 84](../../pocs/SystemDesign-poc/84-geospatial-indexing/README.md) and [H3](https://h3geo.org/docs/).
- **Location update rate:** a driver sends a point every 3 to 5 seconds; at a million drivers that is 200,000 to 330,000 updates per second (estimate), most of them within the same cell. Skip the index write when the cell did not change, and batch the writes.
- **Positions are eventually consistent:** the stored point is seconds old, so "nearest" is approximate, and a driver may already have gone offline. The offer step is the truth: it re-checks the state under a lock, and a decline or timeout is normal, not an error.
- **Offer delivery:** push over a connection with the offer id and deadline; the client may answer after the deadline, which is why accept is checked server-side. Timers belong to a durable delayed store ([Topic 23](../23-delayed-job-scheduler/README.md)) so a restart does not lose a pending timeout. Events and retries: [event-processing core](../../internal-pocs/event-processing-poc/core/).

## 4. Official References

- [H3: hexagonal hierarchical geospatial indexing system](https://h3geo.org/docs/) and its [resolution table](https://h3geo.org/docs/core-library/restable/)
- [S2 Geometry library](https://s2geometry.io/)
- [Wikipedia: Geohash](https://en.wikipedia.org/wiki/Geohash), [Quadtree](https://en.wikipedia.org/wiki/Quadtree), [Hungarian algorithm](https://en.wikipedia.org/wiki/Hungarian_algorithm)
- [Java `Math.floorDiv`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html#floorDiv(long,long)) and [`multiplyExact`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html#multiplyExact(long,long))
- Book: *Designing Data-Intensive Applications*, 1st edition, ch. 3 "Storage and Retrieval" (section "Multi-column indexes" covers geospatial indexes)

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Clarify: scale, update rate, one city or global, straight line or road ETA. State out of scope. |
| 5-10 | Draw the grid with a rider on a cell edge. Walk the ring search and the stopping rule. |
| 10-20 | `Point`, `Cell` with floorDiv, `DriverIndex` interface and errors, `GridIndex.nearest`. Say the cost vs density. |
| 20-27 | Differential test vs brute force; point out the edge, negative and tie cases. |
| 27-35 | Batching window, scoring Strategy, greedy vs optimal example. |
| 35-41 | State table, offer deadline, timeout and re-dispatch, the double-offer race. |
| 41-45 | Surge with a locked quote; production delta (shard by cell, H3, update rate). |

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Index | "use a grid or geohash" | Ring search with the correct stop rule, proof by worked example, differential test against brute force, cost vs density and when to move to quadtree or H3 |
| Geometry | doubles and sqrt | squared `long` with `multiplyExact`, a coordinate bound that makes overflow impossible, `floorDiv` for negatives, deterministic tie-break |
| Matching | nearest driver first | Batching window trade-off, scoring Strategy, greedy gap shown, exact assignment for small batches, a measured simulation with stated setup |
| State and time | a status field | Transition table with every illegal pair tested, offer ids and deadlines, injected scheduler, accept-after-timeout refused, decliners excluded |
| Concurrency | `synchronized` somewhere | Forced interleaving with a Gap hook: defect test then the fix passing the same interleaving, lock order stated |
| Pricing and production | multiplier in the UI | Basis points, capped, monotone, quote locked, smoothing and hysteresis delta; shard by cell, H3, update rate, eventual consistency |

## 7. Key Takeaways

1. A grid index is a list of drivers per square. The nearest search scans rings of squares and stops only when the best distance is strictly below the least distance any outer ring could hold. The first non-empty ring is not enough.
2. Use `long` coordinates with a bound, squared distances with `multiplyExact`, `floorDiv` for cells, and a tie-break on driver id. Test it against brute force on thousands of seeded worlds.
3. Batching lets a later request take a driver an earlier one would have wasted, and greedy can be worse than optimal (20 vs 12). Measured: it does nothing at light load, helps a lot (-22 to -25 percent pickup distance) when drivers are scarce, and always costs a few seconds of waiting.
4. A driver has one state and at most one live offer. An offer has a never-reused id and a deadline; accept is refused after it. A decline or timeout excludes that driver for that request and tries the next best.
5. Check-and-offer under one lock. Prove the race with a Gap hook, then show the fix passes the same interleaving.
6. Surge is a capped, monotone function of demand over supply, in bps; the rider's quote is locked at request time. Smooth it with averaging and hysteresis in production.

**Self-grade: STRONG.** Full spine, worked ring example before code, interface and error contract first, 66 tests (4000-world differential check, exhaustive tight stop bound, probe counters, Hungarian vs brute force on square and rectangular tables, every state pair, a racy defect and its fix, a pinned sim table), and hand mutations that the suite kills (commands.md). Gaps: flat plane only (no quadtree or H3 coded), the sim uses straight-line distance, instant accepts and no cancellations, rating or ETA batches are not provably optimal, no wall-clock `Scheduler`, surge smoothing and hysteresis are design-only, and the maximum-wait expiry is coded but the policy for choosing it is not discussed.

[← Topic 35: Order-Matching Engine](../35-order-matching-engine/) · [Index](../README.md) · [Topic 37: Spreadsheet →](../37-spreadsheet/)
