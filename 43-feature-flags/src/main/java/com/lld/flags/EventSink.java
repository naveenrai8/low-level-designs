package com.lld.flags;

/**
 * Where evaluation events go. An implementation must be fast and must not block, because it is called on the evaluating thread.
 */
public interface EventSink {
    /**
     * Accepts one event. May drop it, but must not block.
     *
     * @param event the event
     */
    void emit(EvaluationEvent event);
}
