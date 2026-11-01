package com.lld.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds seeded random command flows. The generator asks a scratch book which orders are resting, so every cancel and replace it emits is valid. */
final class Flow {

    /** Not instantiable: only static helpers. */
    private Flow() {
    }

    /** Returns {@code count} valid commands from the seed: prices 95..105, small quantities so levels collide a lot. Ids are n0, n1, ... in a fixed order. */
    static List<Command> random(long seed, int count) {
        Random rnd = new Random(seed);
        LimitOrderBook scratch = new LimitOrderBook();
        List<Command> out = new ArrayList<>();
        int next = 0;
        while (out.size() < count) {
            int roll = rnd.nextInt(100);
            Command c;
            if (roll < 60) {
                c = Command.NewOrder.limit("n" + next++, side(rnd), 95 + rnd.nextInt(11), 1 + rnd.nextInt(9));
            } else if (roll < 68) {
                c = Command.NewOrder.ioc("n" + next++, side(rnd), 95 + rnd.nextInt(11), 1 + rnd.nextInt(12));
            } else if (roll < 74) {
                c = Command.NewOrder.fok("n" + next++, side(rnd), 95 + rnd.nextInt(11), 1 + rnd.nextInt(12));
            } else if (roll < 80) {
                c = Command.NewOrder.market("n" + next++, side(rnd), 1 + rnd.nextInt(12));
            } else {
                List<OrderView> all = new ArrayList<>(scratch.resting(Side.BUY));
                all.addAll(scratch.resting(Side.SELL));
                if (all.isEmpty()) {
                    continue;
                }
                OrderView v = all.get(rnd.nextInt(all.size()));
                if (roll < 90) {
                    c = new Command.Cancel(v.id());
                } else if (roll < 95) {
                    c = new Command.Replace(v.id(), v.price(), 1 + rnd.nextLong(v.resting()));
                } else {
                    c = new Command.Replace(v.id(), 95 + rnd.nextInt(11), 1 + rnd.nextInt(12));
                }
            }
            scratch.apply(c);
            out.add(c);
        }
        return out;
    }

    /** Picks BUY or SELL with equal chance. */
    private static Side side(Random rnd) {
        return rnd.nextBoolean() ? Side.BUY : Side.SELL;
    }
}
