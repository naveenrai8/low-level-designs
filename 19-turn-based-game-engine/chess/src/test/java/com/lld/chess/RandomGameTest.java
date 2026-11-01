package com.lld.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Seeded random games: invariants that must hold in every reachable position, not just the hand-picked ones. Deterministic: the same seeds produce the same games on every run. */
class RandomGameTest {

    private static final int GAMES = 60;
    private static final int MAX_PLIES = 250;

    /** Proves that in seeded random games every position survives a FEN round trip, and undo walks the game back to the start exactly. */
    @Test
    void invariantsHoldInRandomGames() {
        SplittableRandom rnd = new SplittableRandom(19);
        int terminal = 0;
        for (int game = 0; game < GAMES; game++) {
            Game g = Game.standard();
            List<String> fens = new ArrayList<>();
            fens.add(g.position().toFen());
            while (!g.status().isOver() && g.moveLog().size() < MAX_PLIES) {
                List<Move> legal = g.position().legalMoves();
                assertFalse(legal.isEmpty(), "in progress implies a legal move");
                Move m = legal.get(rnd.nextInt(legal.size()));
                assertInstanceOf(MoveResult.Applied.class, g.play(m.uci()), m.uci());

                Position p = g.position();
                Position reparsed = Position.fromFen(p.toFen()); // re-validates: one king each, side not to move safe
                assertEquals(p, reparsed);
                assertEquals(TestSupport.legalUci(p), TestSupport.legalUci(reparsed));
                fens.add(p.toFen());
            }
            if (g.status().isOver()) {
                terminal++;
            }
            for (int i = fens.size() - 1; i > 0; i--) {
                assertEquals(fens.get(i), g.position().toFen());
                assertTrue(g.undo());
            }
            assertEquals(Position.START_FEN, g.position().toFen());
            assertFalse(g.undo());
        }
        assertTrue(terminal > 0, "some random games should reach an automatic ending");
    }
}
