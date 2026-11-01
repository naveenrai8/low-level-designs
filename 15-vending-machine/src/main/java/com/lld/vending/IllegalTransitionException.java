package com.lld.vending;

/** Thrown when an event is not allowed in the machine's current state, for example inserting a coin while the product is dropping. The machine is left exactly as it was. */
public final class IllegalTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final State state;
    private final Event event;

    /** Remembers which state and event clashed so callers and tests can check them. */
    public IllegalTransitionException(State state, Event event) {
        super(event + " is not allowed in state " + state);
        this.state = state;
        this.event = event;
    }

    /** Returns the state the machine was in. */
    public State state() {
        return state;
    }

    /** Returns the event that was refused. */
    public Event event() {
        return event;
    }
}
