package com.lld.logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every event in a list. Used by tests and demos to look at exactly what was logged. */
public final class MemoryAppender implements Appender {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    /** Remembers the event. */
    @Override
    public void append(LogEvent event) {
        events.add(event);
    }

    /** Returns a copy of everything logged so far, oldest first. */
    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    /** Returns just the messages, oldest first. */
    public List<String> messages() {
        return events.stream().map(LogEvent::message).toList();
    }
}
