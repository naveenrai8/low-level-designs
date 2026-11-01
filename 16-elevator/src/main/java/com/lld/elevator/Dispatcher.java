package com.lld.elevator;

import java.util.List;

/** Chooses which car answers a hall call. Pure decision: it looks at snapshots and changes nothing. */
@FunctionalInterface
public interface Dispatcher {

    /**
     * Returns the id of the car that should answer {@code call}.
     *
     * <p>{@code eligible} is never empty and holds only cars in NORMAL mode. The answer must be one of their ids.
     */
    int pick(HallCall call, List<CarView> eligible);
}
