package com.lld.elevator;

import java.util.Comparator;
import java.util.List;

/** Sends the car with the fewest pending stops, so work spreads out. Ties go to the closer car, then the lower id. */
public final class LeastLoadedDispatcher implements Dispatcher {

    /** Returns the eligible car with the smallest queue. */
    @Override
    public int pick(HallCall call, List<CarView> eligible) {
        return eligible.stream()
                .min(Comparator.comparingInt(CarView::pending)
                        .thenComparingInt((CarView c) -> Math.abs(c.floor() - call.floor()))
                        .thenComparingInt(CarView::id))
                .orElseThrow().id();
    }
}
