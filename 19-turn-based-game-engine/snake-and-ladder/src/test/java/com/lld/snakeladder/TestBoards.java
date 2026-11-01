package com.lld.snakeladder;

/** Small hand-checkable boards shared by the tests. */
final class TestBoards {

    /** Utility class, so it cannot be created. */
    private TestBoards() {
    }

    /** 20 squares: ladder 3->11, snake 17->5. */
    static final Board SMALL = Board.builder(20).ladder(3, 11).snake(17, 5).build();

    /**
     * Use with a 2-faced die. Rolling 1 first takes the ladder 1->6 over a wall of snakes (4->2, 5->3). Rolling 2 first lands on 2, and from {2, 3} every roll leads back into {2, 3}. A shortest game exists (2 rolls), yet half of all games never end.
     */
    static final Board TRAP = Board.builder(8).ladder(1, 6).snake(4, 2).snake(5, 3).build();

    /** 100 squares, every square in 94..99 a snake head: with a d6 no resting square can reach 100. */
    static Board walled() {
        Board.Builder walled = Board.builder(100);
        for (int head = 94; head <= 99; head++) {
            walled.snake(head, head - 50);
        }
        return walled.build();
    }

    /**
     * 30 squares, snakes from 2..6, 8..12 and 14..18 all back to 1. From 1 the only way past 18 is three sixes in a row (1 -> 7 -> 13 -> 19). Fine one roll at a time, but with the three-sixes forfeit those three sixes are always one turn, so the third one cancels it: nobody ever finishes.
     */
    static Board sixesWall() {
        Board.Builder board = Board.builder(30);
        for (int base = 2; base <= 14; base += 6) {
            for (int head = base; head < base + 5; head++) {
                board.snake(head, 1);
            }
        }
        return board.build();
    }
}
