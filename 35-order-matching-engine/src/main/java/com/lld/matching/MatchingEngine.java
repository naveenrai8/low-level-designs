package com.lld.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single-writer wrapper: ONE engine thread owns the {@link LimitOrderBook}, and any number of caller threads submit commands through a bounded queue.
 * <p>Why: the book itself needs no lock because only one thread ever touches it, and the order in which the engine thread takes commands off the queue IS the order of the world. That order is written to the book's log, so the run can be replayed exactly. See the LMAX architecture article in the README.
 * <p>The queue is a {@link BlockingQueue} (Topic 22): when it is full, {@link #submit} waits, which is backpressure. {@link #submit} does hold one small lock while it waits for room, only to keep "closed" and "enqueue" together. {@link #book()} may be read only after {@link #close()}.
 * <p>If the engine thread ever dies (an {@link Error}), every waiting future is failed and the engine closes itself, so no caller waits forever.
 */
public final class MatchingEngine implements AutoCloseable {

    /** One queued request: the command plus the future the caller waits on. */
    private record Task(Command command, CompletableFuture<Result> future) {
    }

    private static final Task POISON = new Task(null, null);

    private final BlockingQueue<Task> queue;
    private final Book book;
    private final Thread thread;
    private final ReentrantLock gate = new ReentrantLock();
    private boolean closed;

    /** Creates an engine whose queue holds at most {@code capacity} waiting commands. Call {@link #start()} before submitting. */
    public MatchingEngine(int capacity) {
        this(capacity, new LimitOrderBook());
    }

    /** Creates an engine around a given book. Package-private so tests can plug in a book that misbehaves. */
    MatchingEngine(int capacity, Book book) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be above zero");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.book = book;
        this.thread = new Thread(this::run, "matching-engine");
    }

    /** Starts the engine thread and returns this engine. Separate from the constructor so a half-built object is never visible to another thread. */
    public MatchingEngine start() {
        thread.start();
        return this;
    }

    /** The engine thread's loop: take a task, apply it to the book, complete its future. Any exception from one command fails only that future; an Error ends the loop. On the way out, everything still queued is failed. */
    private void run() {
        try {
            while (true) {
                Task task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (task == POISON) {
                    return;
                }
                try {
                    task.future().complete(book.apply(task.command()));
                } catch (RuntimeException e) {
                    task.future().completeExceptionally(e);
                } catch (Error e) {
                    task.future().completeExceptionally(e);
                    return;
                }
            }
        } finally {
            shutDown();
        }
    }

    /**
     * Marks the engine closed and fails every task still in the queue. A producer may be holding the gate while it waits for room, so we keep draining the queue until we can take the gate; that frees the room and lets the producer finish.
     */
    private void shutDown() {
        while (!gate.tryLock()) {
            failQueued();
            Thread.onSpinWait();
        }
        try {
            closed = true;
            failQueued();
        } finally {
            gate.unlock();
        }
    }

    /** Fails the future of every queued task with an IllegalStateException (the stop marker has no future). */
    private void failQueued() {
        List<Task> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (Task t : rest) {
            if (t.future() != null) {
                t.future().completeExceptionally(new IllegalStateException("engine stopped before this command ran"));
            }
        }
    }

    /**
     * Queues a command and returns a future for its {@link Result}. A rejected command completes the future exceptionally with {@link IllegalArgumentException}. Blocks while the queue is full. Throws {@link IllegalStateException} after {@link #close()}, and when called from the engine thread itself (it would wait for room that only it can make).
     * <p>The closed check and the enqueue share one lock so that no task can slip in behind the stop marker and wait forever.
     */
    public CompletableFuture<Result> submit(Command command) {
        if (Thread.currentThread() == thread) {
            throw new IllegalStateException("submit from the engine thread would deadlock the single writer");
        }
        CompletableFuture<Result> future = new CompletableFuture<>();
        gate.lock();
        try {
            if (closed) {
                throw new IllegalStateException("engine is closed");
            }
            queue.put(new Task(command, future));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        } finally {
            gate.unlock();
        }
        return future;
    }

    /**
     * Stops accepting commands, lets everything already queued run, and waits for the engine thread to finish. Safe to call twice. It keeps going even if the calling thread is interrupted (otherwise the engine thread would never get its stop marker); the interrupt flag is restored at the end.
     */
    @Override
    public void close() {
        boolean interrupted = false;
        gate.lock();
        try {
            if (!closed) {
                closed = true;
                while (true) {
                    try {
                        queue.put(POISON);
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            gate.unlock();
        }
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** True while the engine thread is still running. Package-private probe for tests. */
    boolean threadAlive() {
        return thread.isAlive();
    }

    /** Returns the book. Only allowed once the engine thread has stopped (after {@link #close()}); throws {@link IllegalStateException} while it is running, because the book is not thread-safe. */
    public Book book() {
        if (thread.isAlive()) {
            throw new IllegalStateException("read the book only after close()");
        }
        return book;
    }

    /** Returns the accepted-command log (after close). Replaying it into a fresh book gives the same book. */
    public List<Command> log() {
        return book().log();
    }
}
