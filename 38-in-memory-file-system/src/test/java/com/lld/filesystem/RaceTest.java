package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Forced "the tree changed between my lookup and my lock" cases.
 * <p>Each test parks one call (the thread named {@code op}) after it resolved its paths, changes the tree on the test thread, then lets the call go on. The call must notice the change under its locks and fail or retry cleanly.
 */
class RaceTest {
    /** A hook that parks the thread named op at its first hook point (after resolving, before locking) until the test releases it. */
    private static final class Park implements Hook {
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean armed;

        /** Stops the op thread here until released; other threads and unarmed setup calls pass straight through. */
        private void pause() {
            if (armed && Thread.currentThread().getName().equals("op")) {
                parked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** Parks mutating calls before they lock. */
        @Override
        public void beforeLock() {
            pause();
        }

        /** Parks rename right after it resolved both paths. */
        @Override
        public void afterResolve() {
            pause();
        }
    }

    private final Park park = new Park();
    private final FileSystem fs = new InMemoryFileSystem(park);

    /**
     * Runs the op on its own thread, waits until it is parked, runs the interference on this thread, releases the op and returns its outcome.
     *
     * @param op           the call under test
     * @param interference what changes the tree while the op is parked
     * @return the task, already joined
     */
    private Task race(Runnable op, Runnable interference) throws Exception {
        park.armed = true;
        Task t = new Task("op", op);
        t.start();
        assertTrue(park.parked.await(5, TimeUnit.SECONDS), "op never reached its hook");
        interference.run();
        park.release.countDown();
        Task.joinAll(t);
        return t;
    }

    /** write into a directory that is removed meanwhile gives ENOENT and creates nothing. */
    @Test
    void writeIntoARemovedDirectory() throws Exception {
        fs.mkdir("/gone");
        Task t = race(() -> fs.write("/gone/f", "x", WriteMode.OVERWRITE), () -> fs.rmdir("/gone"));
        assertEquals(Errno.ENOENT, t.errno());
        assertEquals(List.of(), fs.ls("/"));
    }

    /** symlink into a directory that is removed meanwhile gives ENOENT and creates nothing. */
    @Test
    void symlinkIntoARemovedDirectory() throws Exception {
        fs.mkdir("/gone");
        Task t = race(() -> fs.symlink("/t", "/gone/l"), () -> fs.rmdir("/gone"));
        assertEquals(Errno.ENOENT, t.errno());
        assertEquals(List.of(), fs.ls("/"));
    }

    /** mkdir inside a directory that was REPLACED by a rename (so it died) gives ENOENT. */
    @Test
    void mkdirIntoADirectoryReplacedByRename() throws Exception {
        fs.mkdir("/a");
        fs.mkdir("/b");
        Task t = race(() -> fs.mkdir("/b/x"), () -> fs.rename("/a", "/b"));
        assertEquals(Errno.ENOENT, t.errno());
        assertEquals(List.of("b"), fs.ls("/"));
        assertEquals(List.of(), fs.ls("/b"));
    }

    /** rm of a file whose name now holds a NEW directory must not delete that directory: it re-resolves and says EISDIR. */
    @Test
    void rmSeesTheNameChange() throws Exception {
        fs.write("/x", "f", WriteMode.OVERWRITE);
        Task t = race(() -> fs.rm("/x"), () -> {
            fs.rm("/x");
            fs.mkdir("/x");
            fs.mkdir("/x/y");
        });
        assertEquals(Errno.EISDIR, t.errno());
        assertEquals(List.of("y"), fs.ls("/x"));
    }

    /** rmdir of a directory whose name now holds a NEW non-empty directory must not remove it: ENOTEMPTY. */
    @Test
    void rmdirSeesTheNameChange() throws Exception {
        fs.mkdir("/d");
        Task t = race(() -> fs.rmdir("/d"), () -> {
            fs.rmdir("/d");
            fs.mkdir("/d");
            fs.write("/d/f", "1", WriteMode.OVERWRITE);
        });
        assertEquals(Errno.ENOTEMPTY, t.errno());
        assertEquals("1", fs.cat("/d/f"));
    }

    /** rename whose DESTINATION name became a directory meanwhile must not replace it: EISDIR, and the directory's content stays. */
    @Test
    void renameSeesTheDestinationChange() throws Exception {
        fs.mkdir("/a");
        fs.mkdir("/b");
        fs.write("/a/f", "1", WriteMode.OVERWRITE);
        Task t = race(() -> fs.rename("/a/f", "/b/x"), () -> {
            fs.mkdir("/b/x");
            fs.mkdir("/b/x/y");
        });
        assertEquals(Errno.EISDIR, t.errno());
        assertEquals(List.of("y"), fs.ls("/b/x"));
        assertEquals("1", fs.cat("/a/f"));
    }

    /** rename whose SOURCE name now holds a different node moves the NEW node, not the one it first saw (which is gone). */
    @Test
    void renameSeesTheSourceChange() throws Exception {
        fs.mkdir("/a");
        fs.mkdir("/b");
        fs.write("/a/f", "old", WriteMode.OVERWRITE);
        Task t = race(() -> fs.rename("/a/f", "/b/g"), () -> {
            fs.rm("/a/f");
            fs.write("/a/f", "new", WriteMode.OVERWRITE);
        });
        assertEquals(null, t.error);
        assertEquals("new", fs.cat("/b/g"));
        assertEquals(List.of(), fs.ls("/a"));
    }

    /** rename into a destination directory that is removed meanwhile gives ENOENT and the file stays where it was. */
    @Test
    void renameIntoARemovedDirectory() throws Exception {
        fs.mkdir("/gone");
        fs.write("/f", "1", WriteMode.OVERWRITE);
        Task t = race(() -> fs.rename("/f", "/gone/f"), () -> fs.rmdir("/gone"));
        assertEquals(Errno.ENOENT, t.errno());
        assertEquals("1", fs.cat("/f"));
    }
}
