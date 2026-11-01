package com.lld.vending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Proves the table: 7 pairs are legal and the other 17 of the 24 are refused. IDLE, SELECTING and PAID are checked on a real machine; DISPENSING through a dispenser probe; COMPLETE and JAM are machine-side events, so for them the real machine is only reachable through the table check {@code require}. */
class TransitionTableTest {

    private static final Map<State, Set<Event>> LEGAL = new EnumMap<>(State.class);

    static {
        LEGAL.put(State.IDLE, Set.of(Event.SELECT));
        LEGAL.put(State.SELECTING, Set.of(Event.INSERT, Event.CANCEL));
        LEGAL.put(State.PAID, Set.of(Event.DISPENSE, Event.CANCEL));
        LEGAL.put(State.DISPENSING, Set.of(Event.COMPLETE, Event.JAM));
    }

    /** Checks all 24 (state, event) pairs against the expected legal list, so no pair is skipped. */
    @Test
    void everyPairMatchesTheExpectedLegalList() {
        int legal = 0;
        for (State s : State.values()) {
            for (Event e : Event.values()) {
                boolean expected = LEGAL.get(s).contains(e);
                assertEquals(expected, TransitionTable.isLegal(s, e), s + " + " + e);
                legal += expected ? 1 : 0;
            }
        }
        assertEquals(7, legal);
    }

    /** Drives a machine into IDLE, SELECTING or PAID. DISPENSING cannot be held from outside, so it has its own probe test. */
    private static Fixtures machineIn(State state, Dispenser d) {
        Fixtures f = new Fixtures(d);
        f.machine.loadCoins(5, 10);
        if (state == State.IDLE) {
            return f;
        }
        f.machine.select("A1");
        if (state == State.SELECTING) {
            return f;
        }
        f.insertAll(100, 25);
        return f;
    }

    /** Runs the customer action that matches an event on the machine; COMPLETE and JAM are machine-side, so they go through require. */
    private static void fire(DefaultVendingMachine m, Event e) {
        switch (e) {
            case SELECT -> m.select("A1");
            case INSERT -> m.insert(5);
            case CANCEL -> m.cancel();
            case DISPENSE -> m.dispense();
            case COMPLETE, JAM -> m.require(e);
        }
    }

    /** For each resting state (IDLE, SELECTING, PAID) every illegal event throws IllegalTransitionException and leaves state and balance untouched. */
    @ParameterizedTest
    @EnumSource(value = State.class, names = {"IDLE", "SELECTING", "PAID"})
    void illegalEventsAreRefusedAndChangeNothing(State state) {
        for (Event e : Event.values()) {
            if (LEGAL.get(state).contains(e)) {
                continue;
            }
            Fixtures f = machineIn(state, Fixtures.working());
            long balanceBefore = f.machine.balance();
            IllegalTransitionException ex = assertThrows(IllegalTransitionException.class, () -> fire(f.machine, e), state + " + " + e);
            assertEquals(state, ex.state());
            assertEquals(e, ex.event());
            assertEquals(state, f.machine.state());
            assertEquals(balanceBefore, f.machine.balance());
        }
    }

    /** While DISPENSING, a dispenser that calls back into the machine on the same thread sees every customer action refused. */
    @Test
    void everyCustomerActionIsRefusedWhileDispensing() {
        List<String> refused = new ArrayList<>();
        Fixtures[] holder = new Fixtures[1];
        Dispenser probe = p -> {
            DefaultVendingMachine m = holder[0].machine;
            assertEquals(State.DISPENSING, m.state());
            for (Event e : Event.values()) {
                if (LEGAL.get(State.DISPENSING).contains(e)) {
                    m.require(e);
                    continue;
                }
                assertThrows(IllegalTransitionException.class, () -> fire(m, e));
                refused.add(e.name());
            }
        };
        holder[0] = machineIn(State.PAID, probe);
        SaleResult r = holder[0].machine.dispense();
        assertEquals(SaleResult.Status.SOLD, r.status());
        assertEquals(List.of("SELECT", "INSERT", "CANCEL", "DISPENSE"), refused);
    }

    /** A pair can lead to two places when the answer depends on data: insert stays or pays, dispense goes on or goes home. */
    @Test
    void dataDependentPairsListBothTargets() {
        assertEquals(Set.of(State.SELECTING, State.PAID), TransitionTable.targets(State.SELECTING, Event.INSERT));
        assertEquals(Set.of(State.DISPENSING, State.IDLE), TransitionTable.targets(State.PAID, Event.DISPENSE));
    }
}
