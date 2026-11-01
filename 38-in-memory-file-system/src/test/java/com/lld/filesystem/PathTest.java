package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Path text rules: dots, slashes, names, absolute only. */
class PathTest {
    private final FileSystem fs = new InMemoryFileSystem();

    /** The basic v1 flow: mkdir, write, ls, cat. */
    @Test
    void mkdirWriteLsCat() {
        fs.mkdir("/docs");
        fs.write("/docs/a.txt", "hi", WriteMode.OVERWRITE);
        assertEquals(List.of("a.txt"), fs.ls("/docs"));
        assertEquals("hi", fs.cat("/docs/a.txt"));
        assertEquals(List.of("docs"), fs.ls("/"));
    }

    /** POSIX says the parent of the root is the root itself, so going up past it changes nothing (POSIX.1-2017 4.13 Pathname Resolution). */
    @Test
    void dotDotPastRootStaysAtRoot() {
        fs.mkdir("/a");
        assertEquals(List.of("a"), fs.ls("/../../.."));
        assertEquals(fs.stat("/").id(), fs.stat("/a/../..").id());
        fs.write("/../../f", "x", WriteMode.OVERWRITE);
        assertEquals("x", fs.cat("/f"));
    }

    /** A dot, repeated slashes and a trailing slash all resolve to the same node. */
    @Test
    void dotAndRepeatedAndTrailingSlashes() {
        fs.mkdir("/a");
        fs.mkdir("/a/b");
        long id = fs.stat("/a/b").id();
        assertEquals(id, fs.stat("//a///b//").id());
        assertEquals(id, fs.stat("/a/./b/.").id());
        assertEquals(id, fs.stat("/a/b/../b").id());
        assertEquals(List.of(), fs.ls("/a/b/"));
    }

    /** A trailing slash on a regular file is ENOTDIR, and on a new file name it is refused too. */
    @Test
    void trailingSlashDemandsADirectory() {
        fs.write("/f", "x", WriteMode.OVERWRITE);
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.stat("/f/"));
        Check.failsCleanly(fs, Errno.EISDIR, () -> fs.write("/g/", "x", WriteMode.OVERWRITE));
        fs.mkdir("/d/");
        assertEquals(List.of("d", "f"), fs.ls("/"));
    }

    /** Names that are empty, dots, or hold a slash are invalid; a relative or empty path is refused. */
    @Test
    void namesAndPathsAreValidated() {
        for (String bad : new String[] {"", ".", "..", "a/b", "a\0b"}) {
            Check.failsCleanly(fs, Errno.EINVAL, () -> PathRules.validateName(bad));
        }
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.mkdir("relative"));
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.mkdir(""));
        Check.failsCleanly(fs, Errno.EEXIST, () -> fs.mkdir("/"));
        Check.failsCleanly(fs, Errno.EEXIST, () -> fs.mkdir("/."));
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.mkdir("/a/.."));
    }

    /** Error kinds for the common mistakes: missing parent, name taken, file used as a directory. */
    @Test
    void errorContract() {
        fs.write("/f", "x", WriteMode.OVERWRITE);
        fs.mkdir("/d");
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.mkdir("/nope/x"));
        Check.failsCleanly(fs, Errno.EEXIST, () -> fs.mkdir("/d"));
        Check.failsCleanly(fs, Errno.EEXIST, () -> fs.mkdir("/f"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.mkdir("/f/x"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.ls("/f"));
        Check.failsCleanly(fs, Errno.EISDIR, () -> fs.read("/d"));
        Check.failsCleanly(fs, Errno.EISDIR, () -> fs.write("/d", "x", WriteMode.OVERWRITE));
        Check.failsCleanly(fs, Errno.EISDIR, () -> fs.rm("/d"));
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.read("/zzz"));
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.write("/nope/f", "x", WriteMode.OVERWRITE));
        Check.failsCleanly(fs, Errno.EBUSY, () -> fs.rmdir("/"));
        Check.failsCleanly(fs, Errno.EBUSY, () -> fs.rm("/"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rmdir("/f"));
        fs.write("/d/x", "1", WriteMode.OVERWRITE);
        Check.failsCleanly(fs, Errno.ENOTEMPTY, () -> fs.rmdir("/d"));
    }

    /** Overwrite replaces, append adds to the end, size is the byte count as a long. */
    @Test
    void overwriteVersusAppendAndSize() {
        fs.write("/f", "abc", WriteMode.OVERWRITE);
        fs.write("/f", "de", WriteMode.APPEND);
        assertEquals("abcde", fs.cat("/f"));
        assertEquals(5L, fs.stat("/f").size());
        fs.write("/f", "z", WriteMode.OVERWRITE);
        assertEquals("z", fs.cat("/f"));
        fs.write("/u", "é", WriteMode.OVERWRITE);
        assertEquals(2L, fs.stat("/u").size());
    }

    /** Callers cannot change a file by editing the array they passed or the array they got back. */
    @Test
    void contentIsCopied() {
        byte[] in = {1, 2, 3};
        fs.write("/f", in, WriteMode.OVERWRITE);
        in[0] = 9;
        byte[] out = fs.read("/f");
        out[1] = 9;
        assertEquals(1, fs.read("/f")[0]);
        assertEquals(2, fs.read("/f")[1]);
    }

    /** Removing and renaming plain files and directories, including replacing rules. */
    @Test
    void rmRmdirRename() {
        fs.mkdir("/a");
        fs.write("/a/f", "1", WriteMode.OVERWRITE);
        fs.write("/g", "2", WriteMode.OVERWRITE);
        fs.rename("/a/f", "/g");
        assertEquals("1", fs.cat("/g"));
        assertEquals(List.of(), fs.ls("/a"));
        fs.mkdir("/b");
        Check.failsCleanly(fs, Errno.EISDIR, () -> fs.rename("/g", "/b"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rename("/b", "/g"));
        fs.rename("/b", "/a");
        assertEquals(List.of("a", "g"), fs.ls("/"));
        fs.rename("/a", "/a");
        fs.rm("/g");
        fs.rmdir("/a");
        assertEquals(List.of(), fs.ls("/"));
        fs.mkdir("/q");
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.rmdir("/q/."));
        Check.failsCleanly(fs, Errno.EBUSY, () -> fs.rmdir("/."));
    }

    /** Moving a directory into its own subtree is refused with EINVAL and changes nothing. */
    @Test
    void cannotMoveADirectoryIntoItself() {
        fs.mkdir("/a");
        fs.mkdir("/a/b");
        fs.mkdir("/a/b/c");
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.rename("/a", "/a/b/c/x"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.rename("/a", "/a/x"));
        Check.failsCleanly(fs, Errno.ENOTEMPTY, () -> fs.rename("/a/b", "/a"));
        Check.failsCleanly(fs, Errno.EBUSY, () -> fs.rename("/", "/z"));
    }

    /** A removed directory's id is never reused, so ids stay a safe lock order. */
    @Test
    void idsAreNeverReused() {
        fs.mkdir("/a");
        long first = fs.stat("/a").id();
        fs.rmdir("/a");
        fs.mkdir("/a");
        assertEquals(true, fs.stat("/a").id() > first);
    }

    /** rename of an entry onto itself does nothing: the tree is unchanged and the directory stays usable afterwards. */
    @Test
    void renameOntoItselfIsANoOp() {
        fs.mkdir("/a");
        fs.mkdir("/a/x");
        List<String> before = fs.snapshot();
        fs.rename("/a", "/a");
        fs.rename("/a/", "/a");
        assertEquals(before, fs.snapshot());
        fs.mkdir("/a/y");
        assertEquals(List.of("x", "y"), fs.ls("/a"));
    }

    /** A NUL in a name is EINVAL for every call that creates a name, including the target of a rename. */
    @Test
    void nulInANameIsRefusedEverywhere() {
        fs.write("/f", "x", WriteMode.OVERWRITE);
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.mkdir("/a\0b"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.write("/a\0b", "x", WriteMode.OVERWRITE));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.symlink("/t", "/a\0b"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.rename("/f", "/a\0b"));
    }

    /** A trailing slash on the source or target of a rename demands a directory: a file cannot be renamed with one. */
    @Test
    void renameOfAFileWithATrailingSlash() {
        fs.write("/f", "x", WriteMode.OVERWRITE);
        fs.mkdir("/g");
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rename("/f", "/new/"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rename("/f/", "/new"));
        fs.rename("/g/", "/h/");
        assertEquals(List.of("f", "h"), fs.ls("/"));
    }

    /** The snapshot sees content (a same-size overwrite changes it), so "unchanged tree" really means byte for byte. */
    @Test
    void snapshotSeesContentChanges() {
        fs.write("/f", "ab", WriteMode.OVERWRITE);
        List<String> before = fs.snapshot();
        fs.write("/f", "cd", WriteMode.OVERWRITE);
        assertEquals(false, before.equals(fs.snapshot()));
        fs.write("/f", "ab", WriteMode.OVERWRITE);
        assertEquals(before, fs.snapshot());
    }
}
