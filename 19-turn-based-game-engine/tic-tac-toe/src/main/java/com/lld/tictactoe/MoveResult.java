package com.lld.tictactoe;

import java.util.List;
import java.util.Objects;

/**
 * What happened to a submitted move. Every expected outcome, including a refused move, is a value, not an exception (Topic 04): the caller {@code switch}es on it and the compiler checks that every case is handled.
 *
 * <p>Exceptions are reserved for caller bugs: an unknown player id, or a null argument.
 */
public sealed interface MoveResult
        permits MoveResult.Accepted, MoveResult.Won, MoveResult.Draw, MoveResult.Rejected {

    /** The move was placed and the game goes on. */
    record Accepted(Player next) implements MoveResult {
        /** Rejects a null next player, so a caller never gets an empty answer. */
        public Accepted {
            Objects.requireNonNull(next, "next");
        }
    }

    /** The move completed a line. {@code line} holds at least K contiguous cells. */
    record Won(Player winner, List<Cell> line) implements MoveResult {
        /** Takes a private copy of the winning line so it cannot change later. */
        public Won {
            Objects.requireNonNull(winner, "winner");
            line = List.copyOf(line);
        }
    }

    /** The move filled the board without a winner. */
    record Draw() implements MoveResult {}

    /** The move was refused and the game is unchanged. */
    record Rejected(Reason reason) implements MoveResult {
        /** Rejects a null reason. */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Checked in this order; the first failing check is reported. */
    enum Reason {
        GAME_OVER,
        NOT_YOUR_TURN,
        OUT_OF_BOUNDS,
        OCCUPIED
    }
}
