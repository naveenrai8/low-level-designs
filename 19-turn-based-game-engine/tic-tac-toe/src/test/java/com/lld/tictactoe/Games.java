package com.lld.tictactoe;

import java.util.List;

/** Test helpers. */
final class Games {

    private Games() {}

    /** Plays "r,c" moves in turn order and returns the last result. */
    static MoveResult playAll(Game game, String... cells) {
        MoveResult last = null;
        for (String rc : cells) {
            String[] p = rc.split(",");
            String who = game.playerToMove().orElseThrow().id();
            last = game.play(who, Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
        }
        return last;
    }

    /** Test helper: builds a config with the given size, win length and number of players (A, B, C and so on). */
    static GameConfig config(int size, int winLength, int players) {
        String symbols = "XOABCDEFGH";
        return new GameConfig(size, winLength,
                java.util.stream.IntStream.range(0, players)
                        .mapToObj(i -> new Player(String.valueOf(symbols.charAt(i)), symbols.charAt(i)))
                        .toList());
    }

    /** Test helper: turns pairs of numbers into a list of cells, e.g. (0,0,1,1) becomes two cells. */
    static List<Cell> cells(int... rc) {
        java.util.ArrayList<Cell> out = new java.util.ArrayList<>();
        for (int i = 0; i < rc.length; i += 2) {
            out.add(new Cell(rc[i], rc[i + 1]));
        }
        return out;
    }
}
