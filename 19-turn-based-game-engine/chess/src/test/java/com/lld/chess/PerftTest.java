package com.lld.chess;

import static com.lld.chess.PerftPositions.KIWIPETE;
import static com.lld.chess.PerftPositions.POSITION_3;
import static com.lld.chess.PerftPositions.POSITION_4;
import static com.lld.chess.PerftPositions.POSITION_4_MIRRORED;
import static com.lld.chess.PerftPositions.POSITION_5;
import static com.lld.chess.PerftPositions.POSITION_6;
import static com.lld.chess.PerftPositions.START;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Move generation is correct iff these counts match. Every expected number is copied from https://www.chessprogramming.org/Perft_Results. Deeper depths are in {@link DeepPerftTest}. */
class PerftTest {

    /** Proves the node counts match the published perft numbers at shallow depths. */
    @ParameterizedTest(name = "{0} d{1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            start      | 1 | 20
            start      | 2 | 400
            start      | 3 | 8902
            start      | 4 | 197281
            kiwipete   | 1 | 48
            kiwipete   | 2 | 2039
            kiwipete   | 3 | 97862
            position3  | 1 | 14
            position3  | 2 | 191
            position3  | 3 | 2812
            position3  | 4 | 43238
            position4  | 1 | 6
            position4  | 2 | 264
            position4  | 3 | 9467
            position4m | 1 | 6
            position4m | 2 | 264
            position4m | 3 | 9467
            position5  | 1 | 44
            position5  | 2 | 1486
            position5  | 3 | 62379
            position6  | 1 | 46
            position6  | 2 | 2079
            position6  | 3 | 89890
            """)
    void nodeCounts(String name, int depth, long expected) {
        assertEquals(expected, Perft.count(Position.fromFen(fen(name)), depth));
    }

    /** Proves the start position depth-4 breakdown (captures, checks, mates) matches the published table. */
    @Test
    void startDepth4Breakdown() {
        // CPW: d4 captures 1576, e.p. 0, castles 0, promotions 0, checks 469, mates 8
        assertEquals(new PerftStats(197_281, 1_576, 0, 0, 0, 469, 8), Perft.stats(Position.start(), 4));
    }

    /** Proves the Kiwipete depth-3 breakdown matches the published table, including en passant and castles. */
    @Test
    void kiwipeteDepth3Breakdown() {
        // CPW: d3 captures 17102, e.p. 45, castles 3162, promotions 0, checks 993, mates 1
        assertEquals(new PerftStats(97_862, 17_102, 45, 3_162, 0, 993, 1), Perft.stats(Position.fromFen(KIWIPETE), 3));
    }

    /** Proves the Kiwipete depth-2 breakdown matches the published table. */
    @Test
    void kiwipeteDepth2Breakdown() {
        // CPW: d2 captures 351, e.p. 1, castles 91, promotions 0, checks 3, mates 0
        assertEquals(new PerftStats(2_039, 351, 1, 91, 0, 3, 0), Perft.stats(Position.fromFen(KIWIPETE), 2));
    }

    /** Proves the position 3 depth-4 breakdown matches the published table, including en passant. */
    @Test
    void position3Depth4Breakdown() {
        // CPW: d4 captures 3348, e.p. 123, castles 0, promotions 0, checks 1680, mates 17
        assertEquals(new PerftStats(43_238, 3_348, 123, 0, 0, 1_680, 17), Perft.stats(Position.fromFen(POSITION_3), 4));
    }

    /** Proves position 4 counts promotions and castles correctly at depths 2 and 3. */
    @Test
    void position4PromotionsAndCastles() {
        // CPW: d2 captures 87, e.p. 0, castles 6, promotions 48, checks 10, mates 0
        assertEquals(new PerftStats(264, 87, 0, 6, 48, 10, 0), Perft.stats(Position.fromFen(POSITION_4), 2));
        // CPW: d3 captures 1021, e.p. 4, castles 0, promotions 120, checks 38, mates 22
        assertEquals(new PerftStats(9_467, 1_021, 4, 0, 120, 38, 22), Perft.stats(Position.fromFen(POSITION_4), 3));
    }

    /** Proves the colour-flipped position 4 gives the same numbers, which catches colour-specific bugs. */
    @Test
    void mirroredPosition4HasIdenticalBreakdown() {
        for (int d = 1; d <= 3; d++) {
            assertEquals(Perft.stats(Position.fromFen(POSITION_4), d), Perft.stats(Position.fromFen(POSITION_4_MIRRORED), d));
        }
    }

    /** Proves the per-move divide counts add up to the plain count. */
    @Test
    void divideSumsToCount() {
        Position kiwi = Position.fromFen(KIWIPETE);
        Map<String, Long> divide = Perft.divide(kiwi, 3);
        assertEquals(48, divide.size());
        assertEquals(97_862L, divide.values().stream().mapToLong(Long::longValue).sum());
    }

    /** Proves the start position has 20 root moves, each counting 1 at depth 1. */
    @Test
    void startDivideAtDepth1IsTwentyOnes() {
        Map<String, Long> divide = Perft.divide(Position.start(), 1);
        assertEquals(20, divide.size());
        divide.values().forEach(n -> assertEquals(1L, n));
    }

    /** Maps a short test name such as "kiwipete" to its FEN string. */
    static String fen(String name) {
        return switch (name) {
            case "start" -> START;
            case "kiwipete" -> KIWIPETE;
            case "position3" -> POSITION_3;
            case "position4" -> POSITION_4;
            case "position4m" -> POSITION_4_MIRRORED;
            case "position5" -> POSITION_5;
            case "position6" -> POSITION_6;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
