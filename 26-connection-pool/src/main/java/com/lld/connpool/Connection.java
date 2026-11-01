package com.lld.connpool;

/**
 * A stand-in for an expensive resource such as a database connection.
 *
 * <p>The pool only needs three things from it: an id for reports, a cheap "are you still alive?" check, and a way to close it. There is no real database in this topic.
 */
public interface Connection {

    /** Returns an id that is unique for this connection, used in leak reports. */
    long id();

    /** Asks the connection if it is still usable (a real one would run a tiny query such as {@code SELECT 1}). Called before every hand-out of an idle connection. */
    boolean isValid();

    /** Closes the connection for good and frees what it holds. The pool never uses a connection after calling this. */
    void close();
}
