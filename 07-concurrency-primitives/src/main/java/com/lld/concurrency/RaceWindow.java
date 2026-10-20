package com.lld.concurrency;

/**
 * Test seam that marks the gap between a check and its act in the deliberately broken classes.
 * Production code passes {@link #NONE}; tests pass a barrier so two threads are <em>forced</em>
 * through the gap together, which turns a 1-in-a-million race into a deterministic failure.
 *
 * <p>The fixed classes take no {@code RaceWindow}: they have no gap to pause in. That absence is
 * the lesson.
 */
@FunctionalInterface
public interface RaceWindow {

    RaceWindow NONE = () -> { };

    void pause();
}
