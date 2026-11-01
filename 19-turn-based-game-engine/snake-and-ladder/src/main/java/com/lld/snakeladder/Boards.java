package com.lld.snakeladder;

/** Named boards used by the tests and the analysis in the README. */
public final class Boards {

    /** Utility class, so it cannot be created. */
    private Boards() {
    }

    /**
     * The 100-square board analysed in DataGenetics, "Mathematical Analysis of Chutes and Ladders" (Nick Berry, 2011): 9 ladders, 10 snakes. The article's results (minimum 7 rolls, mean ~36.2, median 29, mode 20) assume {@link FinishRule#OVERSHOOT_WINS}.
     */
    public static Board dataGenetics() {
        return Board.builder(100)
                .ladder(1, 38).ladder(4, 14).ladder(9, 31).ladder(21, 42).ladder(28, 84)
                .ladder(36, 44).ladder(51, 67).ladder(71, 91).ladder(80, 100)
                .snake(16, 6).snake(48, 26).snake(49, 11).snake(56, 53).snake(62, 19)
                .snake(64, 60).snake(87, 24).snake(93, 73).snake(95, 75).snake(98, 78)
                .build();
    }

    /** A small 30-square board (4 ladders, 4 snakes) for a second, independent Markov-vs-simulation check. */
    public static Board thirtySquare() {
        return Board.builder(30)
                .ladder(3, 22).ladder(5, 8).ladder(11, 26).ladder(20, 29)
                .snake(17, 4).snake(19, 7).snake(21, 9).snake(27, 1)
                .build();
    }
}
