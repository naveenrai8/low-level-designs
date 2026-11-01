package com.lld.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests that MDC labels follow a task onto a pool thread only when copied, and never leak afterwards. */
@Timeout(20)
class MdcTest {

    /** Makes sure one test's labels never leak into the next test on the same thread. */
    @AfterEach
    void clean() {
        Mdc.clear();
    }

    /** Runs a task on a single-thread pool and returns the value it computed. */
    private static <T> T onPool(ExecutorService pool, Callable<T> task) throws Exception {
        return pool.submit(task).get();
    }

    /** Labels appear on log lines. */
    @Test
    void labelsAreStampedOnEvents() {
        MemoryAppender mem = new MemoryAppender();
        Logger log = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, mem)).logger("a");
        Mdc.put("req", "42");
        log.info("x");
        assertEquals(Map.of("req", "42"), mem.events().get(0).context());
    }

    /** The proof of the problem: a plain submit does NOT carry the label to the pool thread. */
    @Test
    void plainThreadLocalDoesNotCrossToExecutorThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Mdc.put("req", "42");
            assertNull(onPool(pool, () -> Mdc.get("req")));
        } finally {
            pool.shutdown();
        }
    }

    /** The wrapper captures at submit time and the label is visible on the pool thread. */
    @Test
    void wrapCarriesLabelsToPoolThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Mdc.put("req", "42");
            assertEquals("42", onPool(pool, Mdc.wrap(() -> Mdc.get("req"))));
        } finally {
            pool.shutdown();
        }
    }

    /** The label is captured when wrapped, not when run: later changes by the submitter do not affect the task. */
    @Test
    void captureHappensAtWrapTime() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Mdc.put("req", "1");
            Callable<String> task = Mdc.wrap(() -> Mdc.get("req"));
            Mdc.put("req", "2");
            assertEquals("1", onPool(pool, task));
        } finally {
            pool.shutdown();
        }
    }

    /** After the task the pooled thread is clean again, so the next unwrapped task sees nothing (no leak between requests). */
    @Test
    void wrapperClearsAfterTask() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Mdc.put("req", "42");
            onPool(pool, Mdc.wrap(() -> Mdc.get("req")));
            assertNull(onPool(pool, () -> Mdc.get("req")));
        } finally {
            pool.shutdown();
        }
    }

    /** Even when the task throws, the worker is restored. */
    @Test
    void wrapperRestoresAfterException() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Mdc.put("req", "42");
            Runnable boom = Mdc.wrap((Runnable) () -> {
                throw new IllegalStateException("x");
            });
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pool.submit(boom).get());
            assertNull(onPool(pool, () -> Mdc.get("req")));
        } finally {
            pool.shutdown();
        }
    }

    /** If the worker had its own labels, the wrapper puts those back, not an empty map. */
    @Test
    void wrapperRestoresWorkersOwnLabels() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            onPool(pool, () -> {
                Mdc.put("worker", "w1");
                return null;
            });
            Mdc.put("req", "42");
            Map<String, String> inside = onPool(pool, Mdc.wrap(Mdc::copy));
            assertEquals(Map.of("req", "42"), inside);
            assertEquals(Map.of("worker", "w1"), onPool(pool, Mdc::copy));
        } finally {
            pool.shutdown();
        }
    }

    /** The wrapping executor does it for every task, and the log line written on the pool thread carries the label. */
    @Test
    void wrappingExecutorStampsPoolThreadLogLines() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            MemoryAppender mem = new MemoryAppender();
            Logger log = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, mem)).logger("a");
            Mdc.put("req", "42");
            AtomicReference<Thread> ran = new AtomicReference<>();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Mdc.executor(pool).execute(() -> {
                ran.set(Thread.currentThread());
                log.info("from pool");
                done.countDown();
            });
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "pool task never ran");
            assertNotSame(Thread.currentThread(), ran.get());
            assertEquals(Map.of("req", "42"), mem.events().get(0).context());
        } finally {
            pool.shutdown();
        }
    }

    /** The scope helper restores the previous value and removes a label that did not exist. The "try" warning is silenced because the scopes are used only for their close, which is the point of the test. */
    @Test
    @SuppressWarnings("try")
    void scopeRestoresOldValue() {
        Mdc.put("k", "outer");
        try (Mdc.Scope s = Mdc.with("k", "inner")) {
            assertEquals("inner", Mdc.get("k"));
            try (Mdc.Scope s2 = Mdc.with("other", "v")) {
                assertEquals("v", Mdc.get("other"));
            }
            assertNull(Mdc.get("other"));
        }
        assertEquals("outer", Mdc.get("k"));
    }

    /** The captured copy is a snapshot: changing the thread's labels later does not change a stamped event. */
    @Test
    void eventContextIsASnapshot() {
        MemoryAppender mem = new MemoryAppender();
        Logger log = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, mem)).logger("a");
        Mdc.put("k", "1");
        log.info("x");
        Mdc.put("k", "2");
        assertEquals("1", mem.events().get(0).context().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> mem.events().get(0).context().put("z", "z"));
    }
}
