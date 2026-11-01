package com.lld.dispatch;

/**
 * Test seam: called at a named point so a test can force one interleaving. Points: "picked" (in the Dispatcher: the drivers for a batch are chosen but no offer exists yet) and "checked" (in the Fleet: the driver was seen AVAILABLE but the offer is not written yet). Production passes {@link #NONE}.
 */
@FunctionalInterface
public interface Gap {

    Gap NONE = point -> { };

    /**
     * Called at a named point; does nothing in production.
     *
     * @param point the name of the point
     */
    void at(String point);
}
