package com.lld.dispatch;

import java.util.EnumSet;
import java.util.Set;

/** The life of a driver, with the table of legal moves. Anything not in the table is refused. */
public enum DriverState {
    /** Not working; not in the grid. */
    OFFLINE,
    /** Working and free; in the grid and eligible for offers. */
    AVAILABLE,
    /** Holding one live offer; out of the grid. */
    OFFERED,
    /** Accepted; driving to the rider. */
    EN_ROUTE,
    /** Rider on board. */
    ON_TRIP;

    /**
     * Lists the states this state may move to.
     *
     * @return the legal targets
     */
    public Set<DriverState> targets() {
        return switch (this) {
            case OFFLINE -> EnumSet.of(AVAILABLE);
            case AVAILABLE -> EnumSet.of(OFFLINE, OFFERED);
            case OFFERED -> EnumSet.of(EN_ROUTE, AVAILABLE);
            case EN_ROUTE -> EnumSet.of(ON_TRIP);
            case ON_TRIP -> EnumSet.of(AVAILABLE);
        };
    }

    /**
     * Throws ILLEGAL_TRANSITION unless the move is in the table. A move to the same state is also illegal.
     *
     * @param from the current state
     * @param to the wanted state
     */
    public static void check(DriverState from, DriverState to) {
        if (!from.targets().contains(to)) {
            throw new DispatchException(DispatchException.Code.ILLEGAL_TRANSITION, from + " -> " + to);
        }
    }
}
