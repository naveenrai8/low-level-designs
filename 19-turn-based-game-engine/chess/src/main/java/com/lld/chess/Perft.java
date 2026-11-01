package com.lld.chess;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** Perft ("performance test"): count every leaf of the legal-move tree to a fixed depth and compare against published numbers. It's the oracle for move generation: one missing capture-promotion or one wrong castling right shows up as a count that's off, and {@link #divide} localises it to a single root move. Source of the expected numbers: chessprogramming.org/Perft_Results. */
public final class Perft {

    /** Not instantiable; this class only holds static helpers. */
    private Perft() {
    }

    /** Counts the leaf positions reachable in exactly {@code depth} moves. Depth 0 is 1 by definition. At depth 1 it just returns the number of legal moves, which saves one level of work. Throws for a negative depth. */
    public static long count(Position p, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0: " + depth);
        }
        if (depth == 0) {
            return 1;
        }
        List<Move> moves = p.legalMoves();
        if (depth == 1) {
            return moves.size(); // bulk count: the legal list's size is the leaf count
        }
        long nodes = 0;
        for (Move m : moves) {
            nodes += count(p.play(m), depth - 1);
        }
        return nodes;
    }

    /** Per-root-move counts, keyed by UCI, in the same shape as Stockfish's {@code go perft N}. */
    public static SortedMap<String, Long> divide(Position p, int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("divide needs depth >= 1: " + depth);
        }
        SortedMap<String, Long> out = new TreeMap<>();
        for (Move m : p.legalMoves()) {
            out.put(m.uci(), count(p.play(m), depth - 1));
        }
        return out;
    }

    /** Counts leaves like {@link #count} but also splits them into captures, en passant, castles, promotions, checks and checkmates, to match the published tables. Needs depth 1 or more. */
    public static PerftStats stats(Position p, int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("stats needs depth >= 1: " + depth);
        }
        long[] acc = new long[7];
        walk(p, depth, acc);
        return new PerftStats(acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], acc[6]);
    }

    /** Walks the tree and adds up the statistics for the leaf moves into {@code acc} (nodes, captures, en passant, castles, promotions, checks, checkmates). */
    private static void walk(Position p, int depth, long[] acc) {
        for (Move m : p.legalMoves()) {
            Position next = p.play(m);
            if (depth > 1) {
                walk(next, depth - 1, acc);
                continue;
            }
            acc[0]++;
            if (m.isCapture()) {
                acc[1]++;
            }
            if (m.kind() == Move.Kind.EN_PASSANT) {
                acc[2]++;
            }
            if (m.isCastle()) {
                acc[3]++;
            }
            if (m.promotion() != null) {
                acc[4]++;
            }
            if (next.inCheck()) {
                acc[5]++;
                if (next.legalMoves().isEmpty()) { // only look for mate when in check: stalemate isn't counted
                    acc[6]++;
                }
            }
        }
    }

    /** Command line entry: {@code java ... com.lld.chess.Perft "<fen>" <depth>} prints Stockfish-format divide output, so the two can be diffed line by line (see commands.md). Exits with code 2 if the arguments are wrong. */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: Perft \"<fen>\" <depth>");
            System.exit(2);
        }
        Position p = Position.fromFen(args[0]);
        int depth = Integer.parseInt(args[1]);
        long total = 0;
        for (var e : divide(p, depth).entrySet()) {
            System.out.println(e.getKey() + ": " + e.getValue());
            total += e.getValue();
        }
        System.out.println();
        System.out.println("Nodes searched: " + total);
    }
}
