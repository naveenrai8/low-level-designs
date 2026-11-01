package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Owner and other permission bits, and the rule that every directory on the path needs search (x). */
class PermissionTest {
    private final FileSystem root = new InMemoryFileSystem();
    private final FileSystem alice = root.as(new User("alice"));
    private final FileSystem bob = root.as(new User("bob"));

    /** Opens the root directory to everyone, then lets alice create her own folder /mine with one file in it. */
    private void setup() {
        root.chmod("/", 0777);
        alice.mkdir("/mine");
        alice.write("/mine/secret", "s", WriteMode.OVERWRITE);
    }

    /** Without x on a directory nobody but the owner side can walk through it, even to a world-readable file. */
    @Test
    void deniedTraversalNeedsSearchOnEveryDirectory() {
        setup();
        alice.chmod("/mine/secret", 0644);
        assertEquals("s", bob.cat("/mine/secret"));
        alice.chmod("/mine", 0744);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.cat("/mine/secret"));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.stat("/mine/secret"));
        assertEquals("s", alice.cat("/mine/secret"));
        assertEquals("s", root.cat("/mine/secret"));
        alice.chmod("/mine", 0755);
        assertEquals("s", bob.cat("/mine/secret"));
    }

    /** Reading a directory needs r, and r alone does not let you open what is inside. */
    @Test
    void listNeedsReadAndReadWithoutSearchIsNotEnough() {
        setup();
        alice.chmod("/mine", 0704);
        assertEquals(List.of("secret"), bob.ls("/mine"));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.cat("/mine/secret"));
        alice.chmod("/mine", 0701);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.ls("/mine"));
    }

    /** Creating, removing and renaming inside a directory needs w AND x on it. */
    @Test
    void changingADirectoryNeedsWriteAndSearch() {
        setup();
        alice.chmod("/mine", 0755);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.mkdir("/mine/x"));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.write("/mine/new", "x", WriteMode.OVERWRITE));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.rm("/mine/secret"));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.rename("/mine/secret", "/moved"));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.symlink("/", "/mine/l"));
        alice.chmod("/mine", 0702);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.mkdir("/mine/x"));
        alice.chmod("/mine", 0703);
        bob.mkdir("/mine/x");
    }

    /** Writing an existing file needs w on the file, reading needs r. */
    @Test
    void fileBitsDecideReadAndWrite() {
        setup();
        alice.chmod("/mine/secret", 0600);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.cat("/mine/secret"));
        alice.chmod("/mine/secret", 0604);
        assertEquals("s", bob.cat("/mine/secret"));
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.write("/mine/secret", "x", WriteMode.APPEND));
        alice.chmod("/mine/secret", 0000);
        Check.failsCleanly(alice, Errno.EACCES, () -> alice.cat("/mine/secret"));
        root.cat("/mine/secret");
    }

    /** Only the owner or the superuser may chmod; a mode above 0777 is invalid. */
    @Test
    void chmodRules() {
        setup();
        Check.failsCleanly(bob, Errno.EPERM, () -> bob.chmod("/mine", 0777));
        Check.failsCleanly(alice, Errno.EINVAL, () -> alice.chmod("/mine", 01777));
        root.chmod("/mine", 0700);
        assertEquals(0700, root.stat("/mine").mode());
        assertEquals("alice", root.stat("/mine").owner());
    }

    /** Find skips directories the caller cannot read and search, instead of failing. */
    @Test
    void findSkipsUnreadableDirectories() {
        setup();
        alice.mkdir("/mine/open");
        alice.write("/mine/open/f", "1", WriteMode.OVERWRITE);
        alice.chmod("/mine/open", 0700);
        assertEquals(List.of("/mine/open"), bob.find("/", FindQuery.all().named("open")));
        assertEquals(List.of(), bob.find("/", FindQuery.all().named("f")));
        assertEquals(List.of("/mine/open/f"), alice.find("/", FindQuery.all().named("f")));
    }

    /** rename needs w on BOTH parents: bob cannot move his own file into alice's folder. */
    @Test
    void renameNeedsWriteOnTheDestinationParent() {
        setup();
        bob.mkdir("/bobs");
        bob.write("/bobs/f", "1", WriteMode.OVERWRITE);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.rename("/bobs/f", "/mine/f"));
        alice.chmod("/mine", 0757);
        bob.rename("/bobs/f", "/mine/f");
        assertEquals("1", root.cat("/mine/f"));
    }

    /** Moving a directory to a NEW parent rewrites its ".." entry, so it needs w on the directory itself (rename(2) EACCES); renaming it inside the same parent does not. */
    @Test
    void movingADirectoryNeedsWriteOnIt() {
        setup();
        bob.mkdir("/p");
        bob.mkdir("/p/dir");
        bob.mkdir("/q");
        bob.chmod("/p/dir", 0555);
        Check.failsCleanly(bob, Errno.EACCES, () -> bob.rename("/p/dir", "/q/dir"));
        bob.rename("/p/dir", "/p/other");
        bob.chmod("/p/other", 0755);
        bob.rename("/p/other", "/q/dir");
        assertEquals(List.of("dir"), root.ls("/q"));
    }

    /** find enters a directory only with BOTH r and x: r alone (0704) and x alone (0701) are each skipped. */
    @Test
    void findNeedsReadAndSearchTogether() {
        setup();
        alice.mkdir("/mine/sub");
        alice.write("/mine/sub/f", "1", WriteMode.OVERWRITE);
        alice.chmod("/mine/sub", 0704);
        assertEquals(List.of("/mine/sub"), bob.find("/", FindQuery.all().named("sub")));
        assertEquals(List.of(), bob.find("/", FindQuery.all().named("f")));
        alice.chmod("/mine/sub", 0701);
        assertEquals(List.of(), bob.find("/", FindQuery.all().named("f")));
        alice.chmod("/mine/sub", 0705);
        assertEquals(List.of("/mine/sub/f"), bob.find("/", FindQuery.all().named("f")));
    }
}
