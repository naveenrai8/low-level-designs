package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** find: name glob, size range, kind, symlink handling, and a differential test against a brute-force walk. */
class FindTest {
    private final FileSystem fs = new InMemoryFileSystem();

    /** Builds a small tree used by several tests. */
    private void tree() {
        fs.mkdir("/src");
        fs.mkdir("/src/sub");
        fs.write("/src/a.java", "12345", WriteMode.OVERWRITE);
        fs.write("/src/b.txt", "", WriteMode.OVERWRITE);
        fs.write("/src/sub/c.java", "1234567890", WriteMode.OVERWRITE);
    }

    /** Name globs match the entry's own name, at any depth. */
    @Test
    void globByName() {
        tree();
        assertEquals(List.of("/src/a.java", "/src/sub/c.java"), fs.find("/", FindQuery.all().named("*.java")));
        assertEquals(List.of("/src/b.txt"), fs.find("/src", FindQuery.all().named("?.t[a-z]t")));
        assertEquals(List.of("/src/sub"), fs.find("/", FindQuery.all().named("su[!a]")));
    }

    /** Size bounds are inclusive and use long bytes; a size filter keeps files only. */
    @Test
    void sizeFilters() {
        tree();
        assertEquals(List.of("/src/a.java", "/src/sub/c.java"), fs.find("/", FindQuery.all().atLeast(5)));
        assertEquals(List.of("/src/a.java"), fs.find("/", FindQuery.all().atLeast(5).atMost(9)));
        assertEquals(List.of("/src/b.txt"), fs.find("/", FindQuery.all().atMost(0)));
        assertEquals(List.of("/src/sub/c.java"), fs.find("/", FindQuery.all().atLeast(10)));
        assertEquals(Errno.EINVAL, Check.errno(() -> FindQuery.all().atLeast(5).atMost(4)));
        assertEquals(Errno.EINVAL, Check.errno(() -> FindQuery.all().atLeast(-1)));
    }

    /** Kind filter and the start path itself: the start is reported when it matches. */
    @Test
    void kindAndStart() {
        tree();
        assertEquals(List.of("/src", "/src/sub"), fs.find("/", FindQuery.all().ofKind(Kind.DIRECTORY).named("s*")));
        assertEquals(List.of("/src/a.java"), fs.find("/src/a.java", FindQuery.all()));
        assertEquals(Errno.ENOENT, Check.errno(() -> fs.find("/nope", FindQuery.all())));
        assertEquals(Errno.EINVAL, Check.errno(() -> fs.find("/", FindQuery.all().named("[x"))));
    }

    /** By default find lists a symlink but does not walk into it, so a link loop cannot hang it. */
    @Test
    void doesNotFollowSymlinksByDefault() {
        tree();
        fs.symlink("/src", "/src/sub/up");
        assertEquals(List.of("/src/sub/up"), fs.find("/", FindQuery.all().named("up")));
        assertEquals(List.of("/src/sub/up"), fs.find("/", FindQuery.all().ofKind(Kind.SYMLINK)));
        assertEquals(List.of("/src/a.java", "/src/sub/c.java"), fs.find("/", FindQuery.all().named("*.java")));
    }

    /** With following on, a link to a directory is walked, and a loop is entered only once. */
    @Test
    void followingSymlinksStopsAtLoops() {
        tree();
        fs.symlink("/src", "/src/sub/up");
        fs.symlink("/src/sub", "/alias");
        List<String> found = fs.find("/", FindQuery.all().named("*.java").following(true));
        assertEquals(List.of("/alias/c.java", "/alias/up/a.java", "/src/a.java", "/src/sub/c.java"), found);
        assertEquals(List.of("/alias/up", "/src/sub/up"), fs.find("/", FindQuery.all().named("up").following(true)));
    }

    /** 300 random trees and queries agree with a plain walk over the snapshot plus the JDK glob matcher. */
    @Test
    void matchesABruteForceWalk() {
        Random r = new Random(3838);
        String[] names = {"a", "b", "ab", "ba", "c.x", "abc"};
        String[] globs = {"*", "a*", "*b", "?", "??", "[ab]*", "[!a]*", "*.x", "a?c", "*c*"};
        for (int round = 0; round < 300; round++) {
            FileSystem f = new InMemoryFileSystem();
            List<String> dirs = new ArrayList<>(List.of("/"));
            for (int k = 0; k < 25; k++) {
                String dir = dirs.get(r.nextInt(dirs.size()));
                String path = (dir.equals("/") ? "" : dir) + "/" + names[r.nextInt(names.length)];
                try {
                    switch (r.nextInt(3)) {
                        case 0 -> {
                            f.mkdir(path);
                            dirs.add(path);
                        }
                        case 1 -> f.write(path, "x".repeat(r.nextInt(12)), WriteMode.OVERWRITE);
                        default -> f.symlink(dirs.get(r.nextInt(dirs.size())), path);
                    }
                } catch (FsException expected) {
                    // name taken or parent is not a directory any more: skip this step
                }
            }
            String glob = globs[r.nextInt(globs.length)];
            long lo = r.nextInt(6);
            long hi = lo + r.nextInt(8);
            boolean sized = r.nextBoolean();
            FindQuery q = FindQuery.all().named(glob);
            if (sized) {
                q = q.atLeast(lo).atMost(hi);
            }
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            List<String> expected = new ArrayList<>();
            // the sized query keeps files only, so the root never matches it
            for (String line : f.snapshot()) {
                String[] p = line.split("\\|");
                String name = p[0].equals("/") ? "" : p[0].substring(p[0].lastIndexOf('/') + 1);
                // the root has an empty name; only an all-star glob matches an empty name
                boolean nameOk = name.isEmpty() ? glob.equals("*") : m.matches(Path.of(name));
                boolean sizeOk = !sized || (p[1].equals("FILE") && Long.parseLong(p[5]) >= lo && Long.parseLong(p[5]) <= hi);
                if (nameOk && sizeOk) {
                    expected.add(p[0]);
                }
            }
            List<String> actual = new ArrayList<>(f.find("/", q));
            expected.sort(null);
            actual.sort(null);
            assertEquals(expected, actual, "round " + round + " glob " + glob);
        }
    }

    /** The start path is followed when it is a symlink (like find -H), and a link found below it is not (like find -P). */
    @Test
    void startLinkIsFollowedButLinksBelowAreNot() {
        tree();
        fs.symlink("/src", "/start");
        assertEquals(List.of("/start/a.java", "/start/sub/c.java"), fs.find("/start", FindQuery.all().named("*.java")));
        fs.symlink("/src", "/src/sub/up");
        assertEquals(List.of("/src/a.java", "/src/sub/c.java"), fs.find("/src", FindQuery.all().named("*.java")));
    }

    /** As in find -name, star and question mark match a leading dot. */
    @Test
    void globMatchesALeadingDot() {
        fs.write("/.hidden", "x", WriteMode.OVERWRITE);
        assertEquals(List.of("/", "/.hidden"), fs.find("/", FindQuery.all().named("*")));
        assertEquals(List.of("/.hidden"), fs.find("/", FindQuery.all().named("?hidden")));
    }

    /** With following on, a directory reachable by two links is listed under both, like find -L. */
    @Test
    void followingListsADiamondTwice() {
        tree();
        fs.symlink("/src/sub", "/one");
        fs.symlink("/src/sub", "/two");
        assertEquals(List.of("/one/c.java", "/src/sub/c.java", "/two/c.java"), fs.find("/", FindQuery.all().named("c.java").following(true)));
    }

    /** A dangling link stays a link when following is on, and a link to a file is matched by the file's size. */
    @Test
    void followingDanglingAndFileLinks() {
        tree();
        fs.symlink("/nowhere", "/dangling");
        fs.symlink("/src/a.java", "/alink");
        assertEquals(List.of("/dangling"), fs.find("/", FindQuery.all().ofKind(Kind.SYMLINK).following(true)));
        assertEquals(List.of("/alink", "/src/a.java"), fs.find("/", FindQuery.all().atLeast(5).atMost(5).following(true)));
        assertEquals(List.of("/src/a.java"), fs.find("/", FindQuery.all().atLeast(5).atMost(5)));
    }
}
