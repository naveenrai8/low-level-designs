package com.lld.elevator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Makes a repeatable list of riders from a seed. The same seed always gives the same list, so every dispatcher can be judged on identical traffic. */
public final class Workload {

    /** Not instantiable: this class only holds static helpers. */
    private Workload() {
    }

    /**
     * Builds riders for ticks 0 to {@code ticks}-1. Each tick a rider arrives with probability {@code percentPerTick}%.
     *
     * <p>Traffic mix, like a morning office: half start at the lobby, a quarter head to the lobby, a quarter go between random floors. {@link Random} with a seed is specified exactly by the JDK (a linear congruential generator), so the numbers do not change between JDK versions.
     */
    public static List<Rider> generate(long seed, int floors, int ticks, int percentPerTick) {
        Random rnd = new Random(seed);
        List<Rider> riders = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            if (rnd.nextInt(100) >= percentPerTick) {
                continue;
            }
            int roll = rnd.nextInt(4);
            int origin;
            int dest;
            if (roll < 2) {
                origin = 0;
                dest = 1 + rnd.nextInt(floors - 1);
            } else if (roll == 2) {
                origin = 1 + rnd.nextInt(floors - 1);
                dest = 0;
            } else {
                origin = rnd.nextInt(floors);
                dest = rnd.nextInt(floors - 1);
                if (dest >= origin) {
                    dest++;
                }
            }
            riders.add(new Rider(riders.size(), t, origin, dest));
        }
        return riders;
    }
}
