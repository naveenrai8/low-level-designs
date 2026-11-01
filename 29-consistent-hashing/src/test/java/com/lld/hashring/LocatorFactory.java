package com.lld.hashring;

import java.util.function.LongFunction;

/** Test helper: names the two implementations so the same test body can run against both. */
enum LocatorFactory {
    /** Ring with 200 vnodes per unit of weight. */
    RING(seed -> new ConsistentHashRing(200, seed)),
    RENDEZVOUS(RendezvousLocator::new);

    private final LongFunction<Locator> maker;

    /** Remembers how to build an empty locator from a seed. */
    LocatorFactory(LongFunction<Locator> maker) {
        this.maker = maker;
    }

    /** Builds an empty locator with the given seed. */
    Locator create(long seed) {
        return maker.apply(seed);
    }
}
