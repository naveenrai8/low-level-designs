package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Symlink rules: following, loops, the hop bound, physical dot-dot, rm and rename of a link. */
class SymlinkTest {
    private final FileSystem fs = new InMemoryFileSystem();

    /** Reading through absolute and relative links reaches the target. */
    @Test
    void followsAbsoluteAndRelativeLinks() {
        fs.mkdir("/d");
        fs.write("/d/f", "data", WriteMode.OVERWRITE);
        fs.symlink("/d/f", "/abs");
        fs.symlink("f", "/d/rel");
        fs.symlink("d", "/dirlink");
        assertEquals("data", fs.cat("/abs"));
        assertEquals("data", fs.cat("/d/rel"));
        assertEquals("data", fs.cat("/dirlink/f"));
        assertEquals(List.of("f", "rel"), fs.ls("/dirlink"));
        assertEquals("f", fs.readlink("/d/rel"));
        assertEquals(Kind.SYMLINK, fs.lstat("/abs").kind());
        assertEquals(Kind.FILE, fs.stat("/abs").kind());
    }

    /** A link that points at itself, or two that point at each other, end in ELOOP and change nothing. */
    @Test
    void loopGivesELOOP() {
        fs.symlink("/self", "/self");
        fs.symlink("/b", "/a");
        fs.symlink("/a", "/b");
        Check.failsCleanly(fs, Errno.ELOOP, () -> fs.read("/self"));
        Check.failsCleanly(fs, Errno.ELOOP, () -> fs.stat("/a"));
        Check.failsCleanly(fs, Errno.ELOOP, () -> fs.ls("/a/x"));
        Check.failsCleanly(fs, Errno.ELOOP, () -> fs.write("/b", "x", WriteMode.OVERWRITE));
        assertEquals(Kind.SYMLINK, fs.lstat("/a").kind());
    }

    /** Linux follows at most 40 links in one path (path_resolution(7)): a chain of 40 works, 41 gives ELOOP. */
    @Test
    void hopBoundIsForty() {
        fs.write("/target", "ok", WriteMode.OVERWRITE);
        String prev = "/target";
        for (int i = 1; i <= 41; i++) {
            fs.symlink(prev, "/l" + i);
            prev = "/l" + i;
        }
        assertEquals("ok", fs.cat("/l40"));
        Check.failsCleanly(fs, Errno.ELOOP, () -> fs.cat("/l41"));
    }

    /** The hop budget is shared across the whole path, not reset for each link. */
    @Test
    void hopBudgetIsSharedAcrossThePath() {
        fs.mkdir("/real");
        fs.write("/real/f", "ok", WriteMode.OVERWRITE);
        String prev = "/real";
        for (int i = 1; i <= 25; i++) {
            fs.symlink(prev, "/d" + i);
            prev = "/d" + i;
        }
        fs.symlink("/d25/f", "/e1");
        assertEquals("ok", fs.cat("/d25/f"));
        for (int i = 2; i <= 20; i++) {
            fs.symlink("/e" + (i - 1), "/e" + i);
        }
        Check.failsCleanly(fs, Errno.ELOOP, () -> fs.cat("/e20"));
    }

    /** Chosen rule: dot-dot is PHYSICAL. After a link to /x/y, "/link/.." is /x, not the directory holding the link. */
    @Test
    void dotDotAfterASymlinkIsPhysical() {
        fs.mkdir("/x");
        fs.mkdir("/x/y");
        fs.mkdir("/other");
        fs.symlink("/x/y", "/other/link");
        assertEquals(fs.stat("/x").id(), fs.stat("/other/link/..").id());
        fs.write("/x/marker", "m", WriteMode.OVERWRITE);
        assertEquals("m", fs.cat("/other/link/../marker"));
    }

    /** rm removes the link itself and leaves the target alone. */
    @Test
    void rmDoesNotFollow() {
        fs.write("/f", "keep", WriteMode.OVERWRITE);
        fs.symlink("/f", "/l");
        fs.rm("/l");
        assertEquals("keep", fs.cat("/f"));
        assertEquals(List.of("f"), fs.ls("/"));
        fs.mkdir("/d");
        fs.symlink("/d", "/dl");
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rmdir("/dl"));
        fs.rm("/dl");
        assertEquals(List.of("d", "f"), fs.ls("/"));
    }

    /** rename moves the link itself; it replaces a link at the destination without touching that link's target. */
    @Test
    void renameDoesNotFollow() {
        fs.write("/f", "keep", WriteMode.OVERWRITE);
        fs.write("/g", "other", WriteMode.OVERWRITE);
        fs.symlink("/f", "/l");
        fs.symlink("/g", "/m");
        long id = fs.lstat("/l").id();
        fs.rename("/l", "/m");
        assertEquals(id, fs.lstat("/m").id());
        assertEquals("/f", fs.readlink("/m"));
        assertEquals("other", fs.cat("/g"));
        assertEquals(List.of("f", "g", "m"), fs.ls("/"));
    }

    /** Writing through a dangling link creates the target, as open(2) does; mkdir over a dangling link is EEXIST. */
    @Test
    void danglingLinks() {
        fs.symlink("/new", "/l");
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.read("/l"));
        Check.failsCleanly(fs, Errno.EEXIST, () -> fs.mkdir("/l"));
        fs.write("/l", "made", WriteMode.OVERWRITE);
        assertEquals("made", fs.cat("/new"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.readlink("/new"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.symlink("", "/e"));
    }

    /** Chosen rule (Linux): a trailing slash makes stat see the directory, and lstat too (POSIX), because "l/" can only mean the directory the link leads to. */
    @Test
    void trailingSlashOnLstatFollowsTheLink() {
        fs.mkdir("/d");
        fs.symlink("/d", "/l");
        assertEquals(fs.stat("/d").id(), fs.stat("/l/").id());
        assertEquals(Kind.DIRECTORY, fs.lstat("/l/").kind());
        assertEquals(Kind.SYMLINK, fs.lstat("/l").kind());
    }

    /** rmdir("/dl/") does NOT remove the target directory: the call sees the link, which is not a directory, so ENOTDIR. */
    @Test
    void rmdirWithTrailingSlashKeepsTheTarget() {
        fs.mkdir("/d");
        fs.symlink("/d", "/dl");
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rmdir("/dl/"));
        assertEquals(List.of("d", "dl"), fs.ls("/"));
    }

    /** rename("/dl/", x) does not move the target directory, and rename(x, "/dl/") does not replace it. */
    @Test
    void renameWithTrailingSlashKeepsTheTarget() {
        fs.mkdir("/d");
        fs.symlink("/d", "/dl");
        fs.write("/e", "e", WriteMode.OVERWRITE);
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rename("/dl/", "/z"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rename("/e", "/dl/"));
        fs.mkdir("/d2");
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rename("/d2", "/dl/"));
        assertEquals(List.of("d", "d2", "dl", "e"), fs.ls("/"));
    }

    /** mkdir("/dangling/") is EEXIST (the name is taken by the link) and does not create the link's target. */
    @Test
    void mkdirWithTrailingSlashOnADanglingLink() {
        fs.symlink("/target", "/dangling");
        Check.failsCleanly(fs, Errno.EEXIST, () -> fs.mkdir("/dangling/"));
        assertEquals(List.of("dangling"), fs.ls("/"));
    }

    /** A trailing slash on a NEW symlink name is ENOENT, and so is a NUL in a target or a bad name. */
    @Test
    void symlinkNameAndTargetRules() {
        Check.failsCleanly(fs, Errno.ENOENT, () -> fs.symlink("/t", "/l/"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.symlink("a\0b", "/l"));
        Check.failsCleanly(fs, Errno.EINVAL, () -> fs.symlink("/t", "/a\0b"));
    }

    /** A link target that ends in a slash must lead to a directory; writing through a dangling "x/" target is refused instead of creating a file. */
    @Test
    void trailingSlashInsideALinkTarget() {
        fs.write("/f", "x", WriteMode.OVERWRITE);
        fs.mkdir("/d");
        fs.symlink("/f/", "/lf");
        fs.symlink("/d/", "/ld");
        fs.symlink("/x/", "/dangling");
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.read("/lf"));
        assertEquals(List.of(), fs.ls("/ld"));
        Check.failsCleanly(fs, Errno.EISDIR, () -> fs.write("/dangling", "x", WriteMode.OVERWRITE));
    }

    /** rm with a trailing slash is ENOTDIR for a file and for a link, and removes nothing. */
    @Test
    void rmWithTrailingSlashIsRefused() {
        fs.write("/f", "x", WriteMode.OVERWRITE);
        fs.symlink("/f", "/l");
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rm("/f/"));
        Check.failsCleanly(fs, Errno.ENOTDIR, () -> fs.rm("/l/"));
        assertEquals(List.of("f", "l"), fs.ls("/"));
    }
}
