# Topic 03: Domain Modeling

Most LLD rounds are lost in minute 8, not minute 40. The candidate draws twelve classes, one per
noun in the prompt (`ParkingLot`, `Floor`, `Spot`, `Vehicle`, `Car`, `Bike`, `Ticket`, `Payment`,
`Gate`…). They're all mutable bags of getters and setters, and none of them can say what must
always be true. Then the follow-up ("now support EV charging spots") forces a rewrite, and the
concurrency question has no answer because nobody knows which object owns which fact. A staff
model is smaller. It separates *things with identity* from *values*, puts every invariant in a
constructor or a single owning method, and makes illegal states unrepresentable.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

### What breaks without deliberate modeling

| Symptom | Root cause | When it bites |
|---|---|---|
| `spot.setVehicle(v)` called from three places; one forgets to update `freeCount` | No single owner of an invariant | First concurrency question |
| `ticket.getEntries().add(...)` from outside corrupts state | Internal mutable collection leaked | The old `review-*_old.md` files flagged this repeatedly |
| `Money` as `double`; `0.1 + 0.2 != 0.3` | Value not modeled as a value | Any billing follow-up (Topic 11) |
| `if (vehicle instanceof Car) … else if (… Bike)` scattered everywhere | Closed hierarchy not declared closed | "Add a truck" follow-up: 6 files change, one is missed |
| `Spot` has `isFree`, `vehicle`, `reservedBy`, `outOfService` booleans | Illegal combinations representable (`isFree && vehicle != null`) | Every bug report |

### What the interviewer is probing

- Can you find the **2–4 types that carry the invariants**, and push everything else to values or enums?
- Do you know which objects have **identity** (a spot, a ticket) and which are **values** (a
  license plate, money, a time range)?
- Is the model **closed where the domain is closed** (vehicle sizes) and **open where it will
  grow** (pricing rules)?

---

## 2. How It Solves It

### Entities vs value objects

| | Entity | Value object |
|---|---|---|
| Equality | By identity (ID) | By all fields |
| Mutability | May change over time (state transitions) | Immutable |
| Java 21 shape | `final class` with an ID, `equals`/`hashCode` on ID only | `record` |
| Examples | `Spot`, `Ticket`, `Account`, `Order` | `LicensePlate`, `Money`, `SpotId`, `TimeRange` |

Rule of thumb: **if two instances with the same fields are interchangeable, it's a value.** Two
`Money(500, INR)` are the same thing. Two tickets for the same plate at the same time aren't.

### Invariants live in constructors (compact record constructors)

```java
public record Money(long minorUnits, Currency currency) {
    public Money {                                   // compact constructor: runs before field assignment
        Objects.requireNonNull(currency, "currency");
        if (minorUnits < 0) throw new IllegalArgumentException("negative money: " + minorUnits);
    }
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency); // overflow = exception, not wraparound
    }
    private void requireSameCurrency(Money o) {
        if (!currency.equals(o.currency)) throw new IllegalArgumentException(currency + " vs " + o.currency);
    }
}

public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        if (!end.isAfter(start)) throw new IllegalArgumentException("end must be after start");
    }
}
```

Once constructed, a `TimeRange` can never be inverted, so no method downstream has to check.
That's the payoff: **validate once at the boundary, then trust the type.** `../21-rate-limiter`
does the same with `RateLimit` (`permits ≥ 1`, `window ≥ permits ns`) and `Decision` (an allowed
decision has zero `retryAfter`).

### Closed hierarchies: sealed interfaces + pattern-matching `switch`

```java
public sealed interface Vehicle permits Car, Motorcycle, Truck {
    LicensePlate plate();
}
public record Car(LicensePlate plate) implements Vehicle {}
public record Motorcycle(LicensePlate plate) implements Vehicle {}
public record Truck(LicensePlate plate, int axles) implements Vehicle {}

static SpotSize requiredSize(Vehicle v) {
    return switch (v) {                       // exhaustive: no default branch
        case Motorcycle m -> SpotSize.SMALL;
        case Car c        -> SpotSize.MEDIUM;
        case Truck t      -> SpotSize.LARGE;
    };
}
```

Add `Bus` to `permits` and **every switch without a `default` fails to compile**. The
"add a new type" follow-up becomes a compiler-guided edit instead of a grep. Don't add `default`
to these switches, because it switches the check off.

### Enums with behaviour

```java
public enum SpotSize {
    SMALL, MEDIUM, LARGE;
    public boolean fits(SpotSize required) { return this.compareTo(required) >= 0; } // relies on declaration order — say so in a comment
}
```

Use an enum when the set is fixed and small and each member needs data or behaviour. Use a sealed
interface when members carry *different* fields (`Truck.axles`).

### Immutability and defensive copies

```java
public final class Ticket {
    private final TicketId id;
    private final List<Charge> charges;                 // owned

    Ticket(TicketId id, List<Charge> charges) {
        this.id = id;
        this.charges = List.copyOf(charges);            // copy IN: caller's later edits can't reach us
    }
    public List<Charge> charges() { return charges; }   // already unmodifiable, safe to hand OUT
}
```

`List.copyOf` returns an **unmodifiable copy** (and rejects `null` elements).
`Collections.unmodifiableList(x)` is only a **view**: if you keep `x` and mutate it, readers see the
change. If the entity must mutate its own list, keep an `ArrayList` privately and return
`List.copyOf(list)`, or expose a query method (`totalCharged()`) instead of the collection.

### Worked example: parking lot, mid-level vs staff model

**Mid-level (noun extraction):**

```
ParkingLot ── Floor* ── Spot* (isFree, vehicle, type, setters)
Vehicle <- Car, Bike, Truck (abstract class, getType())
Ticket (entryTime, exitTime, spot, vehicle, amount, setters)
Payment, Gate, EntryGate, ExitGate, DisplayBoard, Admin, Attendant …
```

Free-count lives in `Floor`, occupancy in `Spot`, and the ticket points at a mutable spot. Three
objects must change together, and nothing owns that.

**Staff:**

```
value:   LicensePlate, SpotId, SpotSize(enum), Money, Vehicle(sealed records)
entity:  Spot        { id, size, state: Free | Occupied(ticketId) }   <- state as a sealed type
entity:  Ticket      { id, plate, spotId, entryAt }                  <- immutable once issued
owner:   ParkingLot  { Map<SpotId,Spot>, free-index by size }        <- the ONE place park/unpark happen
policy:  PricingPolicy (interface)                                   <- the extension point (F1: EV, weekends)
```

```java
sealed interface SpotState permits Free, Occupied {}
record Free() implements SpotState {}
record Occupied(TicketId ticket) implements SpotState {}
```

- `isFree && vehicle != null` **can't be expressed**: the state is one or the other.
- `ParkingLot.park(vehicle)` is the only path that changes occupancy and the free index together,
  so the concurrency answer is one lock (or per-size locks) on `ParkingLot`, not on 500 spots.
  See Topic 08.
- Gates, display boards, admins and attendants are **out of scope** until asked. Name them,
  don't draw them.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong | Fix |
|---|---|---|
| One class per noun | Scatters invariants; burns 10 minutes drawing | Find the owner of each invariant; nouns without behaviour become values, enums, or nothing |
| Getters **and setters** on everything | Any caller can break any invariant | Intention-revealing methods (`park`, `release`) on the owner; no setters |
| `equals` on all fields of an entity | Two snapshots of one ticket are "different"; `HashSet` loses it after mutation | Entity equality = ID only; **mutable fields must never be in `hashCode`** |
| Record holding a mutable `List` | Records are shallowly immutable: `record Order(List<Item> items)` still leaks | `items = List.copyOf(items)` in the compact constructor |
| Abstract class hierarchy for vehicle types with `getType()` + `instanceof` chains | Open hierarchy, and the switch can't be exhaustive | Sealed interface + records |
| Booleans for state (`isActive`, `isCancelled`, `isPaid`) | 2ⁿ combinations, most illegal | An enum or sealed state (Topic 06) |
| `double` for money, `long` millis for "dates" | Rounding; time zones lost | `long` minor units (Topic 11); `Instant` / `LocalDate` |
| Modeling persistence (IDs as `Long` from a DB, JPA annotations) | Irrelevant in a 45-min core | Typed IDs (`record SpotId(String value)`) |

### "I thought X, but actually Y"

- **"Records are immutable."** They're *shallowly* immutable. Final fields, but the referenced
  objects can still mutate.
- **"Unmodifiable = immutable."** `Collections.unmodifiableList` is a read-only *view* over a
  list someone else may still be mutating.
- **"Sealed means final."** Permitted subclasses may themselves be `non-sealed`, which reopens
  the hierarchy below them.
- **"Enum ordinal order is a fine encoding."** Only if you write it down. Reordering the
  constants silently changes `compareTo`. For anything persisted or sent over the wire, use an
  explicit field.

### When NOT to

- **Don't make a value object for every primitive.** `SpotId` earns its place (it prevents
  mixing it up with `TicketId`). `FloorNumber(int)` usually doesn't.
- **Don't seal what the follow-up will extend from outside.** Pricing rules, notification
  channels and plugins are open by nature, so use an interface (Strategy, Topic 05).
- **Don't model the org chart.** `Admin`, `Attendant` and `Customer` classes with no behaviour
  are drawing, not design.

### Interview move

At minute ~5, say: *"Let me separate values from entities and find who owns each invariant.
`ParkingLot` owns occupancy. `Ticket` is immutable once issued. Vehicle types are a closed set,
so I'll seal them. Pricing is the open part, so it's an interface."* That one sentence covers
modeling, concurrency ownership and the extension point before you've written a class.

---

## 4. Official References

- JEP 395, Records: https://openjdk.org/jeps/395
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
- JEP 441, Pattern Matching for `switch`: https://openjdk.org/jeps/441
- `java.lang.Record` (shallow immutability, `equals` contract): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Record.html
- `List.copyOf` and unmodifiable lists: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html#unmodifiable
- `Object.hashCode` / `equals` contract: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html#hashCode()
- Joshua Bloch, *Effective Java* (3rd ed.): "Minimize mutability", "Make defensive copies when needed", "Obey the general contract when overriding equals".
- Eric Evans, *Domain-Driven Design*: Entities vs Value Objects.

---

## 5. Key Takeaways

1. Model the **owners of invariants** first. Most nouns become values, enums, or nothing.
2. **Entity** = identity, equality on ID. **Value** = all fields, immutable, a `record`.
3. Validate in the **compact constructor**, then trust the type everywhere downstream.
4. Closed sets → **sealed interface + exhaustive `switch`** without `default`. The compiler then
   finds every place a new type must be handled.
5. Records are **shallowly** immutable: `List.copyOf` incoming collections.
6. Represent state as **one enum or sealed type**, never a set of booleans.
7. Mark the **open extension point** (usually a policy interface) explicitly. Leave everything
   else closed.

---

[← Topic 02: Staff Signals and Rubric](../02-staff-signals-and-rubric/) · [Index](../README.md) · [Topic 04: Interface-First API and Errors →](../04-interface-first-api-and-errors/)

**Self-grade: ADEQUATE.** The full spine is there, with a worked mid-vs-staff contrast, a gaps
table, when-not-to and an interview move. But it's docs-only with no runnable module proving the
mutability-leak and `hashCode` failures, and modeling has few hard numbers by nature.
