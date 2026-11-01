package com.lld.concurrency;

/**
 * Test seam that marks the gap between a check and its act in the deliberately broken classes.
 * <p>Production code passes {@link #NONE}. Tests pass a barrier so two threads are forced through the gap together, which turns a 1-in-a-million race into a failure that happens every run.
 * <p>The fixed classes take no {@code RaceWindow} because they have no gap to pause in. That absence is the lesson.
 */
@FunctionalInterface
public interface RaceWindow {

    RaceWindow NONE = () -> { };

    /**
     * Called in the gap between the check and the act. Does nothing in production, and waits at a barrier in tests.
     */
    void pause();
}
