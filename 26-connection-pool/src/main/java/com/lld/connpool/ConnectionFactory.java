package com.lld.connpool;

/** Opens new connections. Opening is the slow, failure-prone step that the pool exists to avoid repeating. */
@FunctionalInterface
public interface ConnectionFactory {

    /**
     * Opens one new connection.
     *
     * @throws ConnectionException if the connection cannot be opened (the pool gives the slot back and passes the exception on)
     */
    Connection create();
}
