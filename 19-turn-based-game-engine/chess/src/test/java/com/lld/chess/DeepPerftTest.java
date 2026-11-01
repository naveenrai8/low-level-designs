package com.lld.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** The slow perft depths (about 12 s in total; times in commands.md). Skipped unless run with {@code -Dperft.deep=true}: Surefire forwards Maven's user properties to the forked JVM as system properties. */
@EnabledIfSystemProperty(named = "perft.deep", matches = "true")
class DeepPerftTest {

    /** Proves the deep node counts match the published perft numbers. Opt-in only, because they are slow. */
    @ParameterizedTest(name = "{0} d{1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            start      | 5 | 4865609
            start      | 6 | 119060324
            position3  | 5 | 674624
            position3  | 6 | 11030083
            position4  | 4 | 422333
            position4m | 4 | 422333
            position5  | 4 | 2103487
            position6  | 4 | 3894594
            """)
    void nodeCounts(String name, int depth, long expected) {
        assertEquals(expected, Perft.count(Position.fromFen(PerftTest.fen(name)), depth));
    }

    /** Proves the Kiwipete depth-4 breakdown, the first depth where promotions appear, matches the published numbers. */
    @Test
    void kiwipeteDepth4FirstPromotions() {
        PerftStats s = Perft.stats(Position.fromFen(PerftPositions.KIWIPETE), 4);
        assertEquals(4_085_603, s.nodes());
        assertEquals(15_172, s.promotions()); // CPW: the first depth with promotions
        assertEquals(43, s.checkmates());
    }
}
