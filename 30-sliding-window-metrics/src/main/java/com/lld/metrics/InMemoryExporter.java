package com.lld.metrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An {@link Exporter} that just remembers every snapshot it was given. Used by tests. */
public final class InMemoryExporter implements Exporter {

    private final List<RegistrySnapshot> received = new CopyOnWriteArrayList<>();

    /** Stores the snapshot. */
    @Override
    public void export(RegistrySnapshot snapshot) {
        received.add(snapshot);
    }

    /** Returns a copy of everything received so far, oldest first. */
    public List<RegistrySnapshot> received() {
        return List.copyOf(received);
    }
}
