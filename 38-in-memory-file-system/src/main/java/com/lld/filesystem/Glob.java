package com.lld.filesystem;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny glob matcher for one entry name: {@code *} (any run of characters), {@code ?} (exactly one character), {@code [abc]}, {@code [a-c]}, {@code [!abc]}, and {@code \x} to quote a character.
 * <p>Rules for sets, taken from glob(7): a {@code ]} right after the opening {@code [} (or after {@code [!}) is a plain member, and a {@code -} at the start or end of a set is a plain member too. Inside a set a backslash quotes the next character (our rule: glob(7) leaves it open, and the JDK matcher agrees).
 * <p>{@code **} is OUT: a name never contains {@code /}, so {@code **} would mean the same as {@code *}, and it is treated that way. Alternation {@code {a,b}} is also out; a brace is a plain character here.
 * <p>{@code *} and {@code ?} match a leading dot, as {@code find -name} does (find(1), IEEE PASC interpretation 126).
 * <p>Characters are Unicode code points, so one emoji counts as one {@code ?}.
 * <p>The matcher keeps a set of reachable positions instead of backtracking, so the cost is {@code O(pattern * name)} and a pattern like {@code a*a*a*b} cannot blow up.
 */
public final class Glob {
    private static final char LITERAL = 'L';
    private static final char ONE = '?';
    private static final char STAR = '*';
    private static final char SET = '[';

    /** One parsed pattern piece: a literal, any-one, any-run, or a character set of code point ranges. */
    private record Token(char type, int ch, boolean negated, List<int[]> ranges) {
    }

    private final List<Token> tokens;

    /**
     * Wraps an already parsed token list.
     *
     * @param tokens the parsed pattern
     */
    private Glob(List<Token> tokens) {
        this.tokens = tokens;
    }

    /**
     * Parses a glob.
     *
     * @param pattern the glob text
     * @return the compiled matcher
     * @throws FsException with {@link Errno#EINVAL} for an unclosed or empty set, a reversed range, or a trailing backslash
     */
    public static Glob compile(String pattern) {
        int[] p = pattern.codePoints().toArray();
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < p.length) {
            int c = p[i];
            if (c == '\\') {
                if (i + 1 >= p.length) {
                    throw bad(pattern, "trailing backslash");
                }
                out.add(new Token(LITERAL, p[i + 1], false, null));
                i += 2;
            } else if (c == '*') {
                out.add(new Token(STAR, c, false, null));
                i++;
            } else if (c == '?') {
                out.add(new Token(ONE, c, false, null));
                i++;
            } else if (c == '[') {
                i = parseSet(pattern, p, i, out);
            } else {
                out.add(new Token(LITERAL, c, false, null));
                i++;
            }
        }
        return new Glob(out);
    }

    /**
     * Parses one {@code [...]} set starting at the open bracket and adds it to the token list.
     *
     * @param text the whole pattern, for error messages
     * @param p    the pattern as code points
     * @param at   index of the {@code [}
     * @param out  token list to append to
     * @return the index just after the closing bracket
     */
    private static int parseSet(String text, int[] p, int at, List<Token> out) {
        int i = at + 1;
        boolean negated = i < p.length && p[i] == '!';
        if (negated) {
            i++;
        }
        List<int[]> ranges = new ArrayList<>();
        boolean first = true;
        while (i < p.length && (p[i] != ']' || first)) {
            first = false;
            int lo = p[i];
            if (lo == '\\') {
                i++;
                if (i >= p.length) {
                    throw bad(text, "trailing backslash");
                }
                lo = p[i];
            }
            int hi = lo;
            boolean rangeFollows = i + 2 < p.length && p[i + 1] == '-' && p[i + 2] != ']';
            if (rangeFollows) {
                hi = p[i + 2];
                i += 2;
                if (hi < lo) {
                    throw bad(text, "reversed range");
                }
            }
            ranges.add(new int[] {lo, hi});
            i++;
        }
        if (i >= p.length) {
            throw bad(text, "unclosed [");
        }
        out.add(new Token(SET, '[', negated, ranges));
        return i + 1;
    }

    /**
     * Builds the error for a malformed pattern.
     *
     * @param p      the pattern
     * @param reason what is wrong
     * @return the exception to throw
     */
    private static FsException bad(String p, String reason) {
        return new FsException(Errno.EINVAL, "bad glob '" + p + "': " + reason);
    }

    /**
     * Tells whether the whole name matches the pattern.
     * <p>Worked example for {@code a*b} against {@code axxb}: after {@code a} only position 1 is reachable, after {@code *} positions 1..4 are, after {@code b} only position 4 is, and 4 is the end, so it matches.
     *
     * @param name one entry name
     * @return true when the pattern matches the full name
     */
    public boolean matches(String name) {
        int[] chars = name.codePoints().toArray();
        int n = chars.length;
        boolean[] cur = new boolean[n + 1];
        cur[0] = true;
        for (Token t : tokens) {
            boolean[] next = new boolean[n + 1];
            if (t.type == STAR) {
                boolean seen = false;
                for (int i = 0; i <= n; i++) {
                    seen |= cur[i];
                    next[i] = seen;
                }
            } else {
                for (int i = 0; i < n; i++) {
                    if (cur[i] && accepts(t, chars[i])) {
                        next[i + 1] = true;
                    }
                }
            }
            cur = next;
        }
        return cur[n];
    }

    /**
     * Checks one character against one single-character token.
     *
     * @param t the token (literal, any-one or set)
     * @param c the code point
     * @return true when the token accepts the character
     */
    private static boolean accepts(Token t, int c) {
        if (t.type == ONE) {
            return true;
        }
        if (t.type == LITERAL) {
            return t.ch == c;
        }
        boolean in = false;
        for (int[] r : t.ranges) {
            if (c >= r[0] && c <= r[1]) {
                in = true;
                break;
            }
        }
        return in != t.negated;
    }
}
