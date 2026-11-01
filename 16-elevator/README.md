# Topic 16: Elevator

"Design an elevator" looks like a state-machine drawing exercise. The interviewer is waiting for four things: a car that does not zig-zag (LOOK scheduling), a hall button that only counts for the right direction, a way to compare dispatch strategies with numbers instead of opinions, and fire mode that overrides everything.

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

- **Car**: one elevator cabin. **Floor**: 0 is the lobby.
- **Cab request**: a rider inside a car presses a floor number.
- **Hall call**: a rider on a floor presses UP or DOWN. It says which way they want to go, not where.
- **Dispatcher**: the rule that picks which car answers a hall call.
- **Tick**: one step of simulated time. A car moves at most one floor per tick. Time is virtual (Topic 10): nothing sleeps and nothing reads the wall clock.
- **SCAN / LOOK**: SCAN sweeps one way to the end of the shaft, then back. LOOK sweeps only as far as the last request, then turns.
- **p95 wait**: 95 percent of riders waited this long or less.

## 1. The Problem

### What the interviewer is really probing

- Do you pick a scheduling rule (LOOK) or just "go to the next request"?
- Do you know a hall call has a direction?
- Is the dispatcher separate from the car, so you can swap it and measure?
- Do you treat fire and maintenance as explicit modes with a transition table (Topic 06)?

### v1 requirements

- N cars, F floors. Hall calls and cab requests. Each car runs LOOK on its own queue.
- A dispatcher gives each hall call to one car.
- Fire mode and maintenance mode.

### Clarifying questions (assumed answers)

- Capacity and weight? No, cars are unlimited.
- Door time? A stop costs exactly one tick (the car stays put, doors open); boarding itself is free.
- Can a rider cancel? No.
- Many threads? Yes, requests may come from many threads.

### Out of scope

Door sensors, express cars, destination dispatch, real physics (acceleration), priority riders.

## 2. How It Solves It

### Entities and invariants

- `Car`: floor, direction, mode, three sorted sets (cab, up calls, down calls).
- `Building` (implements `ElevatorSystem`): the cars, the dispatcher, the fire flag, one lock.
- Invariants: a car never moves and stops in the same tick; one tick serves at most one hall call; one hall button (floor and direction) belongs to at most one car; a car's floor is always in range; a pending stop is never lost except by fire or maintenance (which say so); a car in FIRE or MAINTENANCE is never picked by a dispatcher.

### Interface

`ElevatorSystem` has `hallCall`, `cabRequest`, `step`, `setFire`, `setMaintenance`, `cars`, `pending`. Bad floors, a DOWN button at floor 0, an UP button on the top floor and unknown cars throw `IllegalArgumentException`. Illegal mode changes throw `IllegalStateException`. Normal refusals (fire, no car in service) come back as values (`HallResult`).

### Class diagram

```
ElevatorSystem <|-- Building --> Dispatcher <|-- Nearest | LeastLoaded | Zoned
                      |  1..*
                      v
                     Car --> ModeTable (NORMAL | MAINTENANCE | FIRE)
Simulation --> Building, VirtualTicker, Workload(seed), Stats
```

### LOOK, with a worked example first

Car at floor 5, going UP. Cab presses: 7, 3, 9. (This is the exact table in `LookTest.positionTableTickByTick`.)

| Tick | What happens | Floor after | Direction |
|---|---|---|---|
| 0 | 7, 9 are above, keep going | 6 | UP |
| 1 | same | 7 | UP |
| 2 | doors open at 7 (served 7); a stop is its own tick, so the car stays | 7 | UP |
| 3 | go on | 8 | UP |
| 4 | go on | 9 | UP |
| 5 | doors open at 9 (served 9) | 9 | UP |
| 6 | nothing above, 3 is below: turn | 8 | DOWN |
| 7 to 10 | descend | 7, 6, 5, 4 | DOWN |
| 11 | reach 3 | 3 | DOWN |
| 12 | doors open at 3 (served 3) | 3 | DOWN |
| 13 | nothing left | 3 | IDLE |

Serving order: 7, 9, 3. The rule each tick: (1) if something applies here, open the doors and stay (the tick ends); (2) otherwise keep direction if anything is still ahead; (3) otherwise turn if something is behind; (4) otherwise go idle. An idle car heads for the nearest work. A request added mid-trip for a floor still ahead is served on the way; one for a floor already passed waits until after the turn. Why a stop is its own tick: the rider gets in and presses a destination after the doors open. If the car also moved in that tick, it would choose its direction before the press arrived. An earlier version did exactly that and carried an UP rider the wrong way (found in review, now `HallPickupTest`). Cost: each tick is O(log n) per queue (sorted sets).

### Hall call rule

A hall call has a direction. The rule: a car answers an UP call at floor 4 only if it is going UP (or idle), or if it is about to turn at floor 4 because nothing is ahead. A car passing 4 going DOWN with work below skips it, and the call stays queued until the car turns. One tick serves at most one hall call: the one matching the travel direction first, and the opposite one only when the car turns there, so an UP and a DOWN call at one floor get two separate stops and nobody is dragged the wrong way. Pressing a button that a car already holds returns that same car, so one button is never split over two cars. Tests: `upCallIsNotServedByCarGoingDown`, `downCallIsServedByCarGoingDown`, `upCallServedWhenCarTurnsAtThatFloor`.

### Dispatchers (F1)

| Dispatcher | Rule | Weakness |
|---|---|---|
| Nearest | closest car by floor | ignores direction and load |
| Least-loaded | fewest pending stops | may send a far car |
| Zoned | each car owns a band of floors; falls back to nearest if the owner is out of service; must be built with the same car count as the building | idle cars in empty zones |

### Concurrency model

Shared state: all car queues, positions, modes and the fire flag. Invariant: a dispatch decision (read snapshots, pick, write to one car) is atomic. Primitive: one `synchronized` monitor on `Building`; every method is one short critical section and does no I/O. Why one lock: dispatch needs a consistent view of all cars, so per-car locks would need ordering ([Topic 08](../08-multi-object-atomicity/)) for no gain at this size. For monitors see [Java-poc: synchronized and monitors](../../internal-pocs/Java-poc/concurrency/12-synchronized-and-monitors/). Tests (`ConcurrencyTest`): (1) a broken `UnlockedBuilding` with a barrier between pick and write shows two threads both choosing car 0, which is the race; (2) a probe dispatcher asserts `Thread.holdsLock(building)` inside `pick`, so the real code is proven to dispatch under the lock; (3) 8 threads press 4000 distinct hall calls behind a start-gate latch and exactly 4000 are pending. (Least-loaded self-corrects, so a "loads are balanced" check would not have caught a missing lock.)

### Code and tests

| File | Role |
|---|---|
| `src/main/java/com/lld/elevator/ElevatorSystem.java` | interface and error contract |
| `Building.java`, `Car.java` | system, lock, LOOK scheduler |
| `NearestDispatcher.java`, `LeastLoadedDispatcher.java`, `ZonedDispatcher.java` | F1 |
| `Simulation.java`, `Workload.java`, `Stats.java`, `SimulationReport.java`, `VirtualTicker.java` | F2 |
| `ModeTable.java`, `CarMode.java`, `ModeEvent.java` | F3 |
| `LookTest` (12), `FireMaintenanceTest` (10), `DispatcherSimTest` (8), `HallPickupTest` (6), `ConcurrencyTest` (3) | 39 tests |

## 3. The Gaps

### F1: dispatcher strategy

`Dispatcher.pick(call, eligibleSnapshots)` is a pure function over copies, so strategies swap with no change to cars. Nearest, least-loaded and zoned are coded. Not coded: cost-based dispatch (estimated time to arrive, counting direction and stops on the way), which is what real systems do.

### F2: simulation and wait-time p95

Setup: 20 floors, 4 cars, 600 ticks of arrivals at 30 percent per tick, seed 42, 174 riders (morning-office mix: half from the lobby, a quarter to the lobby, a quarter between floors). Measured on JDK 21.0.8, `java -cp target/classes com.lld.elevator.SimulationReport 42`. Wait = ticks from pressing the hall button to the doors opening for that rider.

| Dispatcher | Mean wait | p95 wait | Max wait | Finished at tick |
|---|---|---|---|---|
| nearest | 12.5 | 40 | 47 | 622 |
| least-loaded | 7.5 | 26 | 34 | 636 |
| zoned | 16.2 | 37 | 42 | 622 |

The first version of these numbers (nearest 7.8 / p95 28, least-loaded 7.7 / 24, zoned 13.1 / 33 on seed 42) came from a model with bugs: the car moved in the same tick it opened its doors and could carry a rider the wrong way, and one button could be split over two cars. After the fixes every stop costs a tick and nearest got much worse (mean 7.8 to 12.5, p95 28 to 40), because it ignores load and piles calls on the car nearest the lobby. Lesson: a simulation is only as honest as its model, and the fixed model changed the numbers.

With seed 7: nearest 10.5 mean / p95 34 / max 49, least-loaded 10.2 / 31 / 36, zoned 16.4 / 37 / 42. Least-loaded is best on both seeds here, but the gap on seed 7 is small, so two seeds are not proof. Run many seeds before choosing. Zoned is worst on mean because this traffic is lobby-heavy and the lobby car's band is overloaded.

The simulation is deterministic: same seed gives identical riders (`java.util.Random` with a seed is exactly specified) and identical results (`sameSeedSameResults`). It is one thread of discrete steps: each tick riders press, the building steps, riders board or leave.

p95 method: nearest-rank. Sort the n waits, take the value at rank ceil(0.95 n) (1-based). The result is always a value that really happened. It is computed in integers. Test: for 1..20 in shuffled order, p95 is 19, p100 is 20, p50 is 10. Interpolated percentiles would give 19.05 for the same list.

### F3: fire and maintenance as states

Table in `ModeTable` (anything else throws):

| From | Event | To |
|---|---|---|
| NORMAL | MAINTENANCE_ON | MAINTENANCE |
| NORMAL | FIRE_ON | FIRE |
| MAINTENANCE | MAINTENANCE_OFF | NORMAL |
| MAINTENANCE | FIRE_ON | FIRE |
| FIRE | FIRE_OFF | NORMAL |

Fire: every car drops its queues and drives to floor 0, then opens its doors once (a `FIRE_RECALL` stop); new hall calls return `REJECTED_FIRE`; cab presses return false. Maintenance: the car stops moving, drops cab presses (the model assumes a technician takes an empty car, so riders trapped inside are not unloaded), and its hall calls are handed to other cars (returned as orphans if none can take them). Maintenance cannot be switched on during fire (the table forbids it). A car in maintenance when fire starts goes back to maintenance, not NORMAL, when the fire clears (the car remembers its pre-fire mode); an earlier version silently put it back in service. Design delta not coded: fire service phase 2 (firefighter key), per-car fire flag by a sensor.

### Common mistakes

- Treating a hall call as "go to floor X" with no direction, so a car going down picks up a rider who wants up and drags them the wrong way.
- Reversing as soon as a request appears behind. LOOK finishes what is ahead first.
- Moving in the same tick the doors open, so the car chooses a direction before the rider has pressed a destination.
- Dispatching with a stale view of the cars (read outside the lock).
- Letting the simulation read the wall clock, so no run can be reproduced.
- Reporting only the mean wait. The mean hides the unlucky rider; p95 shows them.

### Trade-offs and production delta

LOOK is fair-ish and low-travel but can starve nobody here, since a call is always eventually ahead of a sweeping car. A single lock is fine for tens of cars. Production adds: door and load sensors, destination dispatch (rider enters the floor in the lobby), a real controller with hard-real-time safety separate from this scheduling layer, and metrics (wait p95, stops per trip).

## 4. Official References

- [Elevator algorithm (SCAN/LOOK), Wikipedia](https://en.wikipedia.org/wiki/Elevator_algorithm)
- [Percentile and the nearest-rank method, Wikipedia](https://en.wikipedia.org/wiki/Percentile)
- [java.util.Random (seeded, specified algorithm), Oracle JDK 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Random.html)
- [JLS 17.1: synchronization and monitors](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.1)
- [Topic 06: State Machines](../06-state-machines/) and [Topic 10: Time and Testability](../10-time-and-testability/) in this guide.

## 5. 45-Minute Interview Timeline

| Minutes | What you do |
|---|---|
| 0 to 5 | Clarify: cars, floors, capacity, hall vs cab, fire |
| 5 to 12 | Entities, hall call with direction, interface and errors |
| 12 to 25 | LOOK on one car with the step table; hall call rule |
| 25 to 33 | Dispatcher interface; nearest, least-loaded, zoned; the lock |
| 33 to 40 | Fire and maintenance as a table; simulation and p95 |
| 40 to 45 | Trade-offs, production delta |

## 6. Senior vs Staff Rubric

| Level | Shows |
|---|---|
| Senior | One car with correct LOOK, hall calls with direction, a dispatcher interface |
| Staff | Also: measured comparison of dispatchers on a seeded workload with p95, an explicit mode table, atomic dispatch under a lock, and honesty that one seed proves little |

## 7. Key Takeaways

- LOOK: keep going while anything is ahead, turn only when nothing is.
- A hall call has a direction; only a car going that way (or turning there) answers it.
- Dispatch is a swappable pure function; judge it with a seeded, virtual-time simulation and p95.
- Fire and maintenance are rows in a table, not scattered ifs.
- Dispatch needs one atomic read-pick-write.

**Self-grade: STRONG.** Full spine, LOOK tested with explicit tables and a randomised invariant, three dispatchers measured on seeded workloads, a mode table, and a deterministic concurrency test. Remaining gap: no capacity, door time is one fixed tick, trapped riders in a maintenance car are not modelled, only two seeds reported, and cost-based dispatch is described not coded.

[← Topic 15: Vending Machine](../15-vending-machine/) · [Index](../README.md) · [Topic 17: LRU / LFU Cache →](../17-lru-lfu-cache/)
