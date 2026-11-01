package com.lld.elevator;

import java.util.Comparator;
import java.util.List;

/** Sends the car whose current floor is closest to the call. Ties go to the lower car id. It ignores which way the car is heading, which is its weakness. */
public final class NearestDispatcher implements Dispatcher {

    /** Returns the closest eligible car by floor distance. */
    @Override
    public int pick(HallCall call, List<CarView> eligible) {
        return eligible.stream()
                .min(Comparator.comparingInt((CarView c) -> Math.abs(c.floor() - call.floor())).thenComparingInt(CarView::id))
                .orElseThrow().id();
    }
}
