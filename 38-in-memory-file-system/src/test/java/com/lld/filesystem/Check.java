package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

/** Small helpers shared by the tests. */
final class Check {
    /**
     * Not meant to be created.
     */
    private Check() {
    }

    /**
     * Runs a call that must fail and checks the error kind AND that the tree did not change.
     *
     * @param fs   the file system whose snapshot is compared
     * @param want the expected error kind
     * @param call the failing call
     */
    static void failsCleanly(FileSystem fs, Errno want, Runnable call) {
        List<String> before = fs.snapshot();
        assertEquals(want, errno(call));
        assertEquals(before, fs.snapshot(), "a failed call must not change the tree");
    }

    /**
     * Runs a call that must fail and returns the error kind.
     *
     * @param call the failing call
     * @return the error kind
     */
    static Errno errno(Runnable call) {
        try {
            call.run();
        } catch (FsException e) {
            return e.errno();
        }
        return fail("expected an FsException");
    }
}
