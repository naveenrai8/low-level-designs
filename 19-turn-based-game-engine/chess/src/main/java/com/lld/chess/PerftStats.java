package com.lld.chess;

/** Perft counts broken down the way the Chess Programming Wiki tables are: every field counts <b>leaf moves at the last ply</b>. Captures include en passant; each promotion piece is a separate move; checks and checkmates describe the position after the leaf move. */
public record PerftStats(long nodes, long captures, long enPassant, long castles, long promotions,
                         long checks, long checkmates) {
}
