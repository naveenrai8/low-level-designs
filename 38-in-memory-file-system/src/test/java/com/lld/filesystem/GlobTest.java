package com.lld.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The glob matcher: examples, bad patterns, and a differential test against the JDK's own glob. */
class GlobTest {
    /** Star, question mark, sets, ranges, negation and quoting behave as documented. */
    @Test
    void examples() {
        assertTrue(Glob.compile("*.txt").matches("a.txt"));
        assertTrue(Glob.compile("*.txt").matches(".txt"));
        assertFalse(Glob.compile("*.txt").matches("a.txt2"));
        assertTrue(Glob.compile("a?c").matches("abc"));
        assertFalse(Glob.compile("a?c").matches("ac"));
        assertTrue(Glob.compile("[a-c]x").matches("bx"));
        assertFalse(Glob.compile("[!a-c]x").matches("bx"));
        assertTrue(Glob.compile("[!a-c]x").matches("dx"));
        assertTrue(Glob.compile("a\\*").matches("a*"));
        assertFalse(Glob.compile("a\\*").matches("ab"));
        assertTrue(Glob.compile("**").matches("anything"));
        assertTrue(Glob.compile("").matches(""));
        assertFalse(Glob.compile("").matches("a"));
    }

    /** Malformed patterns are EINVAL, not silently wrong. */
    @Test
    void badPatterns() {
        for (String bad : new String[] {"[abc", "[]", "[]abc", "[z-a]", "a\\", "[a\\"}) {
            assertEquals(Errno.EINVAL, Check.errno(() -> Glob.compile(bad)), bad);
        }
    }

    /** Sets follow glob(7): a leading ] or a leading or trailing - is a plain member, and a backslash inside a set quotes the next character (our rule, pinned here). */
    @Test
    void setEdgeCases() {
        assertTrue(Glob.compile("[]a]").matches("]"));
        assertTrue(Glob.compile("[]a]").matches("a"));
        assertFalse(Glob.compile("[]a]").matches("b"));
        assertFalse(Glob.compile("[!]a]").matches("]"));
        assertTrue(Glob.compile("[!]a]").matches("b"));
        assertTrue(Glob.compile("[a-]").matches("-"));
        assertTrue(Glob.compile("[-a]").matches("-"));
        assertFalse(Glob.compile("[-a]").matches("b"));
        assertTrue(Glob.compile("[\\]]").matches("]"));
        assertFalse(Glob.compile("[\\]]").matches("\\"));
        assertTrue(Glob.compile("[a\\-c]").matches("-"));
        assertFalse(Glob.compile("[a\\-c]").matches("b"));
    }

    /** One emoji is one character for {@code ?}, and {@code *} and {@code ?} match a leading dot. */
    @Test
    void codePointsAndLeadingDot() {
        assertTrue(Glob.compile("?").matches("\uD83D\uDE00"));
        assertFalse(Glob.compile("??").matches("\uD83D\uDE00"));
        assertTrue(Glob.compile("*").matches(".hidden"));
        assertTrue(Glob.compile("?hidden").matches(".hidden"));
    }

    /** A pattern shaped to make a backtracking matcher explode still answers at once. */
    @Test
    void noExponentialBlowup() {
        String name = "a".repeat(5000);
        assertFalse(Glob.compile("a*a*a*a*a*a*a*b").matches(name));
    }

    /** 5000 random patterns and names give the same answer as {@code FileSystem#getPathMatcher("glob:...")}; names never hold a slash, so {@code **} equals {@code *}. The comparison covers the shared subset only: the generator leaves out a leading {@code ]} and a backslash inside a set, where the JDK differs from glob(7). */
    @Test
    void agreesWithTheJdkMatcher() {
        String[] atoms = {"a", "b", "c", "*", "?", "[ab]", "[!a]", "[a-b]", "[a-]", "[-a]", "**", "\\*", "\\?"};
        String[] letters = {"a", "b", "c", "*", "?", "-", "\uD83D\uDE00"};
        Random r = new Random(38);
        for (int i = 0; i < 5000; i++) {
            StringBuilder p = new StringBuilder();
            for (int k = r.nextInt(5); k >= 0; k--) {
                p.append(atoms[r.nextInt(atoms.length)]);
            }
            StringBuilder n = new StringBuilder();
            for (int k = r.nextInt(6); k > 0; k--) {
                n.append(letters[r.nextInt(letters.length)]);
            }
            PathMatcher jdk = FileSystems.getDefault().getPathMatcher("glob:" + p);
            assertEquals(jdk.matches(Path.of(n.toString())), Glob.compile(p.toString()).matches(n.toString()), "pattern " + p + " name " + n);
        }
    }
}
