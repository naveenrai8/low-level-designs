package com.lld.chess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A game: the position history plus the rules that need history (repetition) or the result of the whole move list (claims, game over). {@link Position} knows the board; {@code Game} knows the story.
 *
 * <p><b>Thread confinement.</b> Not thread-safe: one game is driven by one thread (a server would serialise moves per game, e.g. one actor or lock per game id, as Topic 19 tic-tac-toe's registry does).
 */
public final class Game {

    private static final Pattern UCI = Pattern.compile("([a-h][1-8])([a-h][1-8])([qrbn]?)");

    private final List<Position> positions = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private final Map<String, Integer> occurrences = new HashMap<>();
    private Status status;
    private boolean drawClaimed;

    /** Starts a game from a position. Records it in the history and works out if it is already over (for example a FEN that is already checkmate). */
    private Game(Position start) {
        positions.add(start);
        occurrences.merge(start.repetitionKey(), 1, Integer::sum);
        status = evaluate();
    }

    /** A new game from the standard starting position. */
    public static Game standard() {
        return new Game(Position.start());
    }

    /** A new game from any valid FEN. Throws {@code IllegalArgumentException} if the FEN is invalid. */
    public static Game fromFen(String fen) {
        return new Game(Position.fromFen(fen));
    }

    /** The current position, which is the last one in the history. */
    public Position position() {
        return positions.getLast();
    }

    /** Where the game stands now. */
    public Status status() {
        return status;
    }

    /** The side that delivered mate, if the game ended in checkmate. */
    public Optional<Color> winner() {
        return status == Status.CHECKMATE ? Optional.of(position().sideToMove().opposite()) : Optional.empty();
    }

    /** All moves played so far as UCI text, oldest first. */
    public List<String> moveLog() {
        return moves.stream().map(Move::uci).toList();
    }

    /** Plays a UCI move such as {@code e2e4}, {@code e1g1} (castling) or {@code e7e8q}. The text is <b>matched against the legal-move list</b>, never trusted, so there's no second validator to keep in sync with the generator. {@code null} is {@code MALFORMED}, like any other bad text. */
    public MoveResult play(String uci) {
        if (status.isOver()) {
            return new MoveResult.Rejected(MoveResult.Reason.GAME_OVER);
        }
        Matcher mt = UCI.matcher(uci == null ? "" : uci);
        if (!mt.matches()) {
            return new MoveResult.Rejected(MoveResult.Reason.MALFORMED);
        }
        int from = Square.parse(mt.group(1));
        int to = Square.parse(mt.group(2));
        PieceType promotion = mt.group(3).isEmpty() ? null : PieceType.fromFen(mt.group(3).charAt(0));

        Position current = position();
        boolean promotionSquareMatched = false;
        for (Move m : current.legalMoves()) {
            if (m.from() != from || m.to() != to) {
                continue;
            }
            if (m.promotion() == promotion) {
                apply(current.play(m), m);
                return new MoveResult.Applied(m, status);
            }
            promotionSquareMatched |= m.promotion() != null && promotion == null;
        }
        return new MoveResult.Rejected(promotionSquareMatched
                ? MoveResult.Reason.PROMOTION_PIECE_REQUIRED
                : MoveResult.Reason.ILLEGAL);
    }

    /** What the player to move could claim right now, if anything. Threefold is reported first. The FIDE "claim by announcing the move" forms (9.2.1, 9.3.1) aren't modelled: the claim is on the position already on the board (9.2.2, 9.3.2). */
    public Optional<DrawClaim> claimableDraw() {
        if (status.isOver()) {
            return Optional.empty();
        }
        if (occurrences.getOrDefault(position().repetitionKey(), 0) >= 3) {
            return Optional.of(DrawClaim.THREEFOLD_REPETITION);
        }
        if (position().halfmoveClock() >= 100) {
            return Optional.of(DrawClaim.FIFTY_MOVE_RULE);
        }
        return Optional.empty();
    }

    /** Ends the game in a draw if one is claimable now. */
    public ClaimResult claimDraw() {
        if (status.isOver()) {
            return new ClaimResult.Rejected(ClaimResult.Reason.GAME_OVER);
        }
        Optional<DrawClaim> claim = claimableDraw();
        if (claim.isEmpty()) {
            return new ClaimResult.Rejected(ClaimResult.Reason.NO_DRAW_TO_CLAIM);
        }
        status = claim.get() == DrawClaim.THREEFOLD_REPETITION
                ? Status.DRAW_THREEFOLD_REPETITION
                : Status.DRAW_FIFTY_MOVE;
        drawClaimed = true;
        return new ClaimResult.Claimed(status);
    }

    /** Takes back the last action. After a claim that's the claim itself: the game resumes on the same position, still claimable, and the next undo takes back a move. So a claim made before any move can be withdrawn too. An analysis takeback, outside the Laws (FIDE 9.5.1 forbids withdrawing a claim over the board). False if there's nothing to undo. */
    public boolean undo() {
        if (drawClaimed) {
            drawClaimed = false;
            status = evaluate();
            return true;
        }
        if (moves.isEmpty()) {
            return false;
        }
        Position last = positions.removeLast();
        moves.removeLast();
        occurrences.computeIfPresent(last.repetitionKey(), (k, n) -> n == 1 ? null : n - 1);
        status = evaluate();
        return true;
    }

    /** Records a played move: adds the new position to the history, counts it for repetition and re-checks the status. */
    private void apply(Position next, Move m) {
        positions.add(next);
        moves.add(m);
        occurrences.merge(next.repetitionKey(), 1, Integer::sum);
        status = evaluate();
    }

    /** Automatic endings only, in precedence order. Mate is checked first, so a mating move that also reaches halfmove 150 is mate (FIDE 9.6.2: "unless the last move resulted in checkmate"). */
    private Status evaluate() {
        Position p = position();
        if (p.legalMoves().isEmpty()) {
            return p.inCheck() ? Status.CHECKMATE : Status.STALEMATE;
        }
        if (p.hasInsufficientMaterial()) {
            return Status.DRAW_INSUFFICIENT_MATERIAL;
        }
        if (occurrences.getOrDefault(p.repetitionKey(), 0) >= 5) {
            return Status.DRAW_FIVEFOLD_REPETITION;
        }
        if (p.halfmoveClock() >= 150) {
            return Status.DRAW_SEVENTY_FIVE_MOVE;
        }
        return Status.IN_PROGRESS;
    }
}
