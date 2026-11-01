package com.lld.hashring;

/** Thrown when a key is looked up but no node has been added yet, because there is nowhere to send it. */
public final class NoNodesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a fixed message. */
    public NoNodesException() {
        super("no nodes in the ring");
    }
}
