# Topic 03: Domain Modeling

Most LLD rounds are lost at minute 8, not minute 40. The candidate draws twelve classes, one for each noun in the prompt (`ParkingLot`, `Floor`, `Spot`, `Vehicle`, `Car`, `Bike`, `Ticket`, `Payment`, `Gate`…). Each is a bag of getters and setters, and none can say what must always be true. Then the follow-up ("now support EV charging spots") forces a rewrite. The thread-safety question also has no answer, because nobody knows which object owns which fact. A staff model is smaller. It separates *things with an identity* from *plain values*. It puts every rule in a constructor or in one owning method. And it makes wrong states impossible to build: if two fields can never both be true, the code has no way to set them that way.

**Words used here.** An *entity* is a thing with its own identity, like one specific ticket. A *value object* is a thing defined only by its fields, like `Money(500, INR)`. An *invariant* is a rule that must always be true, such as "a spot is never free and occupied at once". A *record* is a Java class for data that never changes. *Immutable* means it cannot change after it is built.

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
| `spot.setVehicle(v)` is called from three places; one forgets `freeCount` | No single owner of a rule | The first thread-safety question |
| `ticket.getEntries().add(...)` from outside corrupts the ticket | The inner list was handed out | The old `review-*_old.md` files flagged this again and again |
| `Money` stored as `double`; `0.1 + 0.2 != 0.3` | Money is not modeled as its own value | Any billing follow-up (Topic 11) |
| `if (vehicle instanceof Car) … else if (… Bike)` repeated everywhere | A fixed set of types is not declared fixed | "Add a truck": 6 files change and one is missed |
| `Spot` has `isFree`, `vehicle`, `reservedBy`, `outOfService` | Wrong combinations can be set (`isFree && vehicle != null`) | Every bug report |

### What the interviewer is probing

- Can you find the **2 to 4 types that carry the rules**, and make everything else a value or an enum?
- Do you know which objects have an **identity** (a spot, a ticket) and which are **values** (a license plate, money, a time range)?
- Is the model **fixed where the domain is fixed** (vehicle sizes) and **open where it will grow** (pricing rules)?

---

## 2. How It Solves It

### Entities vs value objects

| | Entity | Value object |
|---|---|---|
| Equality | By identity (ID) | By all fields |
| Mutability | Can change over time (for example free to occupied) | Never changes |
| Java 21 shape | `final class` with an ID; `equals` and `hashCode` use the ID only | `record` |
| Examples | `Spot`, `Ticket`, `Account`, `Order` | `LicensePlate`, `Money`, `SpotId`, `TimeRange` |

Rule of thumb: **if two objects with the same fields are interchangeable, it is a value.** Two `Money(500, INR)` are the same thing. Two tickets for the same plate at the same time are not.

### Rules live in constructors (compact record constructors)

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

Once built, a `TimeRange` can never have its end before its start, so no later method has to check. That is the payoff: **check once when the object is built, then trust the type.** `../21-rate-limiter` does the same with `RateLimit` (`permits ≥ 1`, `window ≥ permits ns`) and `Decision` (an allowed decision has zero `retryAfter`).

### Fixed sets of types: sealed interfaces and `switch`

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

*Sealed* means only the listed types may implement the interface. Add `Bus` to `permits` and **every switch without a `default` stops compiling**. The compiler then lists every place to fix, so you do not have to search by hand. Do not add `default` to these switches, because it turns that check off.

### Enums with behaviour

```java
public enum SpotSize {
    SMALL, MEDIUM, LARGE;
    public boolean fits(SpotSize required) { return this.compareTo(required) >= 0; } // relies on declaration order — say so in a comment
}
```

Use an enum when the set is small and fixed and each member needs some data or behaviour. Use a sealed interface when members carry *different* fields (`Truck.axles`).

### Immutability and copying

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

Example: `var items = new ArrayList<>(List.of(a)); var order = new Order(items); items.add(b);` If `Order` stored `items` directly, the order now has two items without anyone calling the order. With `List.copyOf` it still has one.

`List.copyOf` returns an **unmodifiable copy** (and rejects `null` elements). `Collections.unmodifiableList(x)` is only a **view**: if you keep `x` and mutate it, readers see the change. If the entity must mutate its own list, keep an `ArrayList` privately and return `List.copyOf(list)`, or expose a query method (`totalCharged()`) instead of the collection.

### Worked example: parking lot, mid-level vs staff model

**Mid-level (one class per noun):**

```
ParkingLot ── Floor* ── Spot* (isFree, vehicle, type, setters)
Vehicle <- Car, Bike, Truck (abstract class, getType())
Ticket (entryTime, exitTime, spot, vehicle, amount, setters)
Payment, Gate, EntryGate, ExitGate, DisplayBoard, Admin, Attendant …
```

The free count lives in `Floor`, occupancy lives in `Spot`, and the ticket points at a spot that can change. Three objects must change together, and nothing owns that.

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

- `isFree && vehicle != null` **cannot happen**: the state is `Free` or `Occupied`, never both.
- `ParkingLot.park(vehicle)` is the only way to change occupancy and the free index together. So the thread-safety answer is one lock (or one per size) on `ParkingLot`, not 500 locks on spots. See Topic 08.
- Gates, display boards, admins and attendants are **out of scope** until asked. Name them but do not draw them.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong | Fix |
|---|---|---|
| One class per noun | Spreads the rules around and burns 10 minutes drawing | Find who owns each rule; nouns with no behaviour become values, enums, or nothing |
| Getters **and setters** on everything | Any caller can break any rule | Methods named for the action (`park`, `release`) on the owner; no setters |
| `equals` on all fields of an entity | Two copies of one ticket look "different"; a `HashSet` loses it after a change | Compare entities by ID only; **never put changing fields in `hashCode`** |
| Record holding a changeable `List` | A record only locks its own fields: `record Order(List<Item> items)` still leaks | `items = List.copyOf(items)` in the compact constructor |
| Abstract class tree for vehicles with `getType()` and `instanceof` chains | Anyone can add a type, and the switch cannot check it covers them all | Sealed interface and records |
| Booleans for state (`isActive`, `isCancelled`, `isPaid`) | 3 booleans give 8 combinations and most make no sense | One enum or sealed state (Topic 06) |
| `double` for money, `long` millis for "dates" | Rounding errors; time zones lost | `long` minor units (Topic 11); `Instant` or `LocalDate` |
| Modeling the database (`Long` IDs, JPA annotations) | Does not matter in a 45-minute round | Typed IDs (`record SpotId(String value)`) |

### "I thought X, but actually Y"

- **"Records are immutable."** Only one level deep. The fields are final, but a list inside a record can still change.
- **"Unmodifiable = immutable."** `Collections.unmodifiableList` is a read-only *view*. Someone else may still be changing the list underneath.
- **"Sealed means final."** A permitted subclass can be marked `non-sealed`, which lets anyone extend it again.
- **"Enum order is a fine encoding."** Only if you write it down. Reordering the constants silently changes `compareTo`. For anything saved or sent over the network, use an explicit field.

### When NOT to

- **Don't wrap every primitive in a value type.** `SpotId` earns its place, because it stops you mixing it up with `TicketId`. `FloorNumber(int)` usually does not.
- **Don't seal what a follow-up will extend from outside.** Pricing rules, notification channels and plugins are open by nature, so use a plain interface (Strategy, Topic 05).
- **Don't model the org chart.** `Admin`, `Attendant` and `Customer` classes with no behaviour are drawing, not design.

### Interview move

At about minute 5, say: *"Let me separate values from entities and find who owns each rule. `ParkingLot` owns occupancy. `Ticket` never changes once issued. Vehicle types are a fixed set, so I'll seal them. Pricing is the open part, so it's an interface."* That one sentence covers the model, who owns thread safety, and the extension point before you have written a class.

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

1. Find the **owner of each rule** first. Most nouns become values, enums, or nothing.
2. **Entity** = identity, equality on ID. **Value** = all fields, immutable, a `record`.
3. Check in the **compact constructor**, then trust the type everywhere after.
4. For a fixed set, use a **sealed interface and a `switch` with no `default`**. The compiler then finds every place a new type must be handled.
5. Records lock only one level: use `List.copyOf` on incoming lists.
6. Show state as **one enum or sealed type**, never a pile of booleans.
7. Mark the **one open extension point** (usually a policy interface) clearly. Keep everything else closed.

---

[← Topic 02: Staff Signals and Rubric](../02-staff-signals-and-rubric/) · [Index](../README.md) · [Topic 04: Interface-First API and Errors →](../04-interface-first-api-and-errors/)

**Self-grade: ADEQUATE.** The full spine is there, with a worked mid-vs-staff contrast, a gaps table, when-not-to and an interview move. But it's docs-only with no runnable module proving the mutability-leak and `hashCode` failures, and modeling has few hard numbers by nature.
