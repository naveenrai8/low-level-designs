package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Per-directory locking: forced interleavings with hooks and barriers, no sleeps. */
class ConcurrencyTest {
    /** How long a barrier that the correct code may legitimately never fill waits (a wait on a lock, not a sleep). */
    private static final long GRACE_MS = 300;

    /**
     * Waits at a barrier that MUST fill: both threads are known to arrive. If the partner never comes, the test fails instead of quietly carrying on.
     *
     * @param b the barrier
     */
    private static void strict(CyclicBarrier b) {
        try {
            b.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("the partner thread never reached the barrier", e);
        }
    }

    /**
     * Waits at a barrier that the correct code may never fill, because the partner is blocked on a lock we hold; gives up after a short grace period.
     *
     * @param b the barrier
     */
    private static void lenient(CyclicBarrier b) {
        try {
            b.await(GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // the partner is blocked behind a lock we hold: carry on
        }
    }

    /**
     * Counts how many of the two tasks succeeded.
     *
     * @param a first task
     * @param b second task
     * @return 0, 1 or 2
     */
    private static int winners(Task a, Task b) {
        return (a.error == null ? 1 : 0) + (b.error == null ? 1 : 0);
    }

    /** Two threads create the same directory after both saw it missing: exactly one wins, the other gets EEXIST. */
    @Test
    void concurrentMkdirSameNameExactlyOneWins() throws Exception {
        CyclicBarrier b = new CyclicBarrier(2);
        FileSystem fs = new InMemoryFileSystem(new Hook() {
            /** Both threads must be here, with their unlocked checks done, before either locks. */
            @Override
            public void beforeLock() {
                strict(b);
            }
        });
        Task t1 = new Task("t1", () -> fs.mkdir("/d"));
        Task t2 = new Task("t2", () -> fs.mkdir("/d"));
        t1.start();
        t2.start();
        Task.joinAll(t1, t2);
        assertEquals(1, winners(t1, t2));
        assertEquals(Errno.EEXIST, t1.error == null ? t2.errno() : t1.errno());
        assertEquals(List.of("d"), fs.ls("/"));
    }

    /** Two creators of the same new file both succeed (the loser appends to the winner's file) and no bytes are lost. */
    @Test
    void concurrentCreatingWritesLoseNothing() throws Exception {
        CyclicBarrier b = new CyclicBarrier(2);
        FileSystem fs = new InMemoryFileSystem(new Hook() {
            /** Both threads must be here before either locks. */
            @Override
            public void beforeLock() {
                strict(b);
            }
        });
        Task t1 = new Task("t1", () -> fs.write("/f", "a", WriteMode.APPEND));
        Task t2 = new Task("t2", () -> fs.write("/f", "b", WriteMode.APPEND));
        t1.start();
        t2.start();
        Task.joinAll(t1, t2);
        assertNull(t1.error);
        assertNull(t2.error);
        assertEquals(2L, fs.stat("/f").size());
        assertEquals(List.of("f"), fs.ls("/"));
    }

    /** Opposite renames /a/x to /b/x and /b/y to /a/y, both holding their first lock at once, do not deadlock because locks go in node id order. */
    @Test
    void oppositeRenamesDoNotDeadlock() throws Exception {
        CyclicBarrier lockStep = new CyclicBarrier(2);
        CyclicBarrier firstHeld = new CyclicBarrier(2);
        AtomicBoolean armed = new AtomicBoolean();
        FileSystem fs = new InMemoryFileSystem(new Hook() {
            /** Both renames must be here before either locks, so they really start together. */
            @Override
            public void beforeLock() {
                if (armed.get()) {
                    strict(lockStep);
                }
            }

            /** In the correct code the second thread is blocked on the first lock, so this barrier may never fill; with call-order locking it fills and the run deadlocks. */
            @Override
            public void afterFirstLock(long dirId) {
                if (armed.get()) {
                    lenient(firstHeld);
                }
            }
        });
        fs.mkdir("/a");
        fs.mkdir("/b");
        fs.write("/a/x", "1", WriteMode.OVERWRITE);
        fs.write("/b/y", "2", WriteMode.OVERWRITE);
        armed.set(true);
        Task t1 = new Task("t1", () -> fs.rename("/a/x", "/b/x"));
        Task t2 = new Task("t2", () -> fs.rename("/b/y", "/a/y"));
        t1.start();
        t2.start();
        Task.joinAll(t1, t2);
        assertNull(t1.error);
        assertNull(t2.error);
        assertEquals(List.of("y"), fs.ls("/a"));
        assertEquals(List.of("x"), fs.ls("/b"));
        assertEquals("1", fs.cat("/b/x"));
        assertEquals("2", fs.cat("/a/y"));
    }

    /** Two moves whose lock sets do not overlap (a into b, b into a) would together make a loop; the tree-wide directory-move lock lets exactly one win and the other gets EINVAL. */
    @Test
    void crossingDirectoryMovesCannotMakeALoop() throws Exception {
        CyclicBarrier resolved = new CyclicBarrier(2);
        CyclicBarrier checked = new CyclicBarrier(2);
        AtomicBoolean armed = new AtomicBoolean();
        FileSystem fs = new InMemoryFileSystem(new Hook() {
            /** Both renames must have resolved their paths (seeing the old tree) before either takes a lock. */
            @Override
            public void afterResolve() {
                if (armed.get()) {
                    strict(resolved);
                }
            }

            /** Holds each rename here until the other has also passed its checks, or a short grace passes if it is blocked on the directory-move lock. */
            @Override
            public void beforeMutate() {
                if (armed.get()) {
                    lenient(checked);
                }
            }
        });
        fs.mkdir("/p");
        fs.mkdir("/q");
        fs.mkdir("/p/a");
        fs.mkdir("/q/b");
        armed.set(true);
        Task t1 = new Task("t1", () -> fs.rename("/p/a", "/q/b/a"));
        Task t2 = new Task("t2", () -> fs.rename("/q/b", "/p/a/b"));
        t1.start();
        t2.start();
        Task.joinAll(t1, t2);
        assertEquals(1, winners(t1, t2));
        assertEquals(Errno.EINVAL, t1.error == null ? t2.errno() : t1.errno());
        // root, p, q, and a and b (one nested in the other): everything is still reachable from the root
        assertEquals(5, fs.snapshot().size(), fs.snapshot().toString());
    }

    /** A call parked while holding the lock of /a does not stop a call in /b: locks are per directory. */
    @Test
    void differentDirectoriesDoNotBlockEachOther() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FileSystem fs = new InMemoryFileSystem(new Hook() {
            /** The thread named slow stops here while it holds /a's lock. */
            @Override
            public void beforeMutate() {
                if (Thread.currentThread().getName().equals("slow")) {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        fs.mkdir("/a");
        fs.mkdir("/b");
        Task slow = new Task("slow", () -> fs.mkdir("/a/x"));
        slow.start();
        inside.await();
        fs.mkdir("/b/y");
        assertEquals(List.of("y"), fs.ls("/b"));
        release.countDown();
        Task.joinAll(slow);
        assertNull(slow.error);
        assertEquals(List.of("x"), fs.ls("/a"));
    }

    /**
     * Waits for the start gate.
     *
     * @param gate the latch released by the test
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Eight threads behind a start gate each append 100 times to one file: the size is exactly 800, so append is atomic. */
    @Test
    void appendsAreAtomic() throws Exception {
        FileSystem fs = new InMemoryFileSystem();
        fs.write("/log", "", WriteMode.OVERWRITE);
        CountDownLatch gate = new CountDownLatch(1);
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Task t = new Task("w" + i, () -> {
                awaitGate(gate);
                for (int k = 0; k < 100; k++) {
                    fs.write("/log", "x", WriteMode.APPEND);
                }
            });
            tasks.add(t);
            t.start();
        }
        gate.countDown();
        Task.joinAll(tasks.toArray(new Task[0]));
        for (Task t : tasks) {
            assertNull(t.error);
        }
        assertEquals(800L, fs.stat("/log").size());
    }

    /** Eight threads shuffle eight files between four directories behind a start gate: nothing hangs, no worker fails, no file is lost or duplicated, and the content survives. */
    @Test
    void randomRenamesKeepEveryFileOnce() throws Exception {
        FileSystem fs = new InMemoryFileSystem();
        String[] dirs = {"/d0", "/d1", "/d2", "/d3"};
        for (String d : dirs) {
            fs.mkdir(d);
        }
        for (int i = 0; i < 8; i++) {
            fs.write("/d0/f" + i, "v" + i, WriteMode.OVERWRITE);
        }
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger moved = new AtomicInteger();
        List<Task> tasks = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            long seed = w;
            Task t = new Task("r" + w, () -> {
                Random r = new Random(seed);
                awaitGate(gate);
                for (int k = 0; k < 2000; k++) {
                    int f = r.nextInt(8);
                    String from = dirs[r.nextInt(4)] + "/f" + f;
                    String to = dirs[r.nextInt(4)] + "/f" + f;
                    try {
                        fs.rename(from, to);
                        moved.incrementAndGet();
                    } catch (FsException e) {
                        // the only legal failure is "the file is in another directory right now"
                        assertEquals(Errno.ENOENT, e.errno());
                    }
                }
            });
            tasks.add(t);
            t.start();
        }
        gate.countDown();
        Task.joinAll(tasks.toArray(new Task[0]));
        for (Task t : tasks) {
            assertNull(t.error, "worker " + t.getName());
        }
        assertTrue(moved.get() > 0);
        assertEquals(8, fs.find("/", FindQuery.all().ofKind(Kind.FILE)).size());
        for (int i = 0; i < 8; i++) {
            List<String> where = fs.find("/", FindQuery.all().named("f" + i));
            assertEquals(1, where.size());
            assertEquals("v" + i, fs.cat(where.get(0)));
        }
    }
}
