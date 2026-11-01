package com.lld.elevator;

import java.util.List;

/** Prints the README table: the same seeded traffic through each dispatcher. Run with {@code java -cp target/classes com.lld.elevator.SimulationReport [seed]}. */
public final class SimulationReport {

    /** Not instantiable: this class only holds static helpers. */
    private SimulationReport() {
    }

    /** Runs the three dispatchers on one seeded workload (20 floors, 4 cars, 600 ticks of arrivals at 30% per tick) and prints a Markdown table. */
    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        int floors = 20;
        int cars = 4;
        List<Rider> riders = Workload.generate(seed, floors, 600, 30);
        System.out.println("seed=" + seed + " riders=" + riders.size());
        System.out.println("| Dispatcher | Riders | Mean wait | p95 wait | Max wait | Finished at tick |");
        System.out.println("|---|---|---|---|---|---|");
        row(Simulation.run("nearest", riders, floors, cars, new NearestDispatcher()));
        row(Simulation.run("least-loaded", riders, floors, cars, new LeastLoadedDispatcher()));
        row(Simulation.run("zoned", riders, floors, cars, new ZonedDispatcher(floors, cars)));
    }

    /** Prints one table row. */
    private static void row(SimResult r) {
        System.out.printf("| %s | %d | %.1f | %d | %d | %d |%n", r.dispatcher(), r.riders(), r.meanWait(), r.p95Wait(), r.maxWait(), r.endTick());
    }
}
