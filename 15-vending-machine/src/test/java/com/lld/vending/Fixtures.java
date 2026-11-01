package com.lld.vending;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared test setup: a standard machine with coins 5, 10, 25, 100, a frozen clock and a recording telemetry. */
final class Fixtures {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final Product COLA = new Product("A1", "Cola", 125);

    final ShelfInventory shelf = new ShelfInventory();
    final List<MachineEvent> events = new ArrayList<>();
    DefaultVendingMachine machine;

    /** Builds a machine with the given dispenser; the shelf holds 3 colas and the box is empty. */
    Fixtures(Dispenser dispenser) {
        shelf.load("A1", 3);
        machine = new DefaultVendingMachine(List.of(COLA), Set.of(5L, 10L, 25L, 100L), shelf, dispenser, events::add, CLOCK);
    }

    /** A dispenser that always works. */
    static Dispenser working() {
        return p -> { };
    }

    /** A dispenser that always jams. */
    static Dispenser jamming() {
        return p -> {
            throw new DispenseJamException("stuck spiral");
        };
    }

    /** Inserts each coin in order. */
    void insertAll(long... coins) {
        for (long c : coins) {
            machine.insert(c);
        }
    }
}
