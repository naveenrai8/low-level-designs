package com.lld.mistakes;

/**
 * Test seam marking the window inside a deliberately broken class where another thread (or a
 * reader) can slip in. Production passes {@link #NONE}. Tests pass a barrier or a probe, which
 * turns a rare interleaving into a deterministic one. Fixed classes have no gap, so they take no
 * {@code Gap}.
 */
@FunctionalInterface
public interface Gap {

    Gap NONE = () -> { };

    void pause();
}
