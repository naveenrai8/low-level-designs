package com.lld.vending;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The one place that says which (state, event) pairs are legal and where each may lead. Anything not listed is illegal.
 *
 * <p>This is the "explicit transition table" shape from Topic 06. A pair can lead to more than one state because the answer depends on data: inserting a coin stays in SELECTING until the money covers the price, and pressing dispense goes to DISPENSING unless the machine cannot make change.
 */
public final class TransitionTable {

    private static final Map<State, Map<Event, Set<State>>> TABLE = build();

    private TransitionTable() {}

    /** Builds the table once. Reading it top to bottom is the whole behaviour of the machine. */
    private static Map<State, Map<Event, Set<State>>> build() {
        Map<State, Map<Event, Set<State>>> t = new EnumMap<>(State.class);
        for (State s : State.values()) {
            t.put(s, new EnumMap<>(Event.class));
        }
        t.get(State.IDLE).put(Event.SELECT, Set.of(State.SELECTING));
        t.get(State.SELECTING).put(Event.INSERT, Set.of(State.SELECTING, State.PAID));
        t.get(State.SELECTING).put(Event.CANCEL, Set.of(State.IDLE));
        t.get(State.PAID).put(Event.DISPENSE, Set.of(State.DISPENSING, State.IDLE));
        t.get(State.PAID).put(Event.CANCEL, Set.of(State.IDLE));
        t.get(State.DISPENSING).put(Event.COMPLETE, Set.of(State.IDLE));
        t.get(State.DISPENSING).put(Event.JAM, Set.of(State.IDLE));
        return t;
    }

    /** Returns the states this event may lead to from this state. The set is empty when the pair is illegal. */
    public static Set<State> targets(State from, Event event) {
        return TABLE.get(from).getOrDefault(event, Set.of());
    }

    /** Returns true when the event is allowed at all in this state. */
    public static boolean isLegal(State from, Event event) {
        return !targets(from, event).isEmpty();
    }
}
