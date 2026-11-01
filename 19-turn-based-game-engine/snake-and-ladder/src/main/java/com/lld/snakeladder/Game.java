package com.lld.snakeladder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * One game: players take turns in order until someone reaches the last square.
 *
 * <p>There are no player decisions. The dice decide everything, so the whole API is {@link #takeTurn()}. The game is deterministic given its {@link Dice}: the same rolls always produce the same {@link #history()}.
 *
 * <p><b>Not thread-safe, by design.</b> A game is driven by one loop. A server hosting many games confines each game to one thread or serialises its turns behind a per-game lock (the tic-tac-toe module shows that registry).
 */
public final class Game {

    private final Board board;
    private final List<Player> players;
    private final int[] positions;
    private final Dice dice;
    private final FinishRule finishRule;
    private final TurnRules turnRules;
    private final List<TurnResult> history = new ArrayList<>();
    private int current;
    private Player winner;

    /** Creates the game from a builder that has already been checked. */
    private Game(Builder b) {
        this.board = b.board;
        this.players = List.copyOf(b.players);
        this.positions = new int[players.size()];
        this.dice = b.dice;
        this.finishRule = b.finishRule;
        this.turnRules = b.turnRules;
    }

    /** Starts a game builder for the given board. */
    public static Builder builder(Board board) {
        return new Builder(board);
    }

    /**
     * Plays the current player's turn: one roll, or several with {@link TurnRules#extraTurnOnSix}.
     *
     * @throws IllegalStateException if the game is already over, or the dice return a value outside {@code 1..faces}
     */
    public TurnResult takeTurn() {
        if (winner != null) {
            throw new IllegalStateException("game over: " + winner.name() + " has won");
        }
        int index = current;
        int start = positions[index];
        int at = start;
        int topFaces = 0;
        boolean forfeited = false;
        boolean won = false;
        List<Step> steps = new ArrayList<>(1);
        while (true) {
            int roll = roll();
            boolean top = roll == dice.faces();
            if (top) {
                topFaces++;
            }
            if (turnRules.threeSixesForfeit() && topFaces == 3) {
                steps.add(new Step(roll, at, at, at)); // recorded so replay sees every roll
                at = start;
                forfeited = true;
                break;
            }
            int landed = finishRule.land(at, roll, board.lastSquare());
            int to = board.destination(landed);
            steps.add(new Step(roll, at, landed, to));
            at = to;
            if (at == board.lastSquare()) {
                won = true;
                break;
            }
            if (!(top && turnRules.extraTurnOnSix())) {
                break;
            }
        }
        positions[index] = at;
        Player player = players.get(index);
        if (won) {
            winner = player;
        } else {
            current = (index + 1) % players.size();
        }
        TurnResult result = new TurnResult(player, steps, start, at, forfeited, won);
        history.add(result);
        return result;
    }

    /** Rolls the dice once and checks the result is a real face. A misbehaving die fails here, loudly. */
    private int roll() {
        int roll = dice.roll();
        if (roll < 1 || roll > dice.faces()) {
            throw new IllegalStateException("dice returned " + roll + " for a " + dice.faces() + "-faced die");
        }
        return roll;
    }

    /** Gives the winner, or empty while the game is still on. */
    public Optional<Player> winner() {
        return Optional.ofNullable(winner);
    }

    /** Whose turn is next. After the game ends, the winner. */
    public Player currentPlayer() {
        return players.get(current);
    }

    /** A snapshot: player → square, in turn order. */
    public Map<Player, Integer> positions() {
        Map<Player, Integer> snapshot = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            snapshot.put(players.get(i), positions[i]);
        }
        return snapshot;
    }

    /** The event log: every turn so far, oldest first. */
    public List<TurnResult> history() {
        return List.copyOf(history);
    }

    /** Collects players, dice and rules, then builds a checked game. */
    public static final class Builder {

        private final Board board;
        private final List<Player> players = new ArrayList<>();
        private Dice dice;
        private FinishRule finishRule = FinishRule.OVERSHOOT_WINS;
        private TurnRules turnRules = TurnRules.NONE;
        private boolean finishable; // memo: the board check is O(squares x faces), the rest is cheap

        /** Starts a builder for this board. Defaults are overshoot wins and no turn rules. */
        private Builder(Board board) {
            this.board = Objects.requireNonNull(board, "board");
        }

        /** Adds players in turn order. Names must be unique, which {@link #build()} checks. */
        public Builder players(String... names) {
            for (String name : names) {
                players.add(new Player(name));
            }
            return this;
        }

        /** Required. There is no default, so a game never picks up hidden, unseeded randomness. */
        public Builder dice(Dice dice) {
            this.dice = Objects.requireNonNull(dice, "dice");
            finishable = false;
            return this;
        }

        /** Sets how the end of the board works. The default is overshoot wins. */
        public Builder finishRule(FinishRule finishRule) {
            this.finishRule = Objects.requireNonNull(finishRule, "finishRule");
            finishable = false;
            return this;
        }

        /** Sets the house rules for a six. The default is none. */
        public Builder turnRules(TurnRules turnRules) {
            this.turnRules = Objects.requireNonNull(turnRules, "turnRules");
            finishable = false;
            return this;
        }

        /**
         * Builds a fresh game. A builder may be reused to start many games with the same setup; the board check then runs once, not per game.
         *
         * @throws IllegalArgumentException if there are no players, two share a name, the dice don't suit the finish rule, or a player could get stuck forever under the finish rule and turn rules
         */
        public Game build() {
            if (players.isEmpty()) {
                throw new IllegalArgumentException("a game needs at least one player");
            }
            Set<String> names = new HashSet<>();
            for (Player player : players) {
                if (!names.add(player.name())) {
                    throw new IllegalArgumentException("duplicate player name: " + player.name());
                }
            }
            Objects.requireNonNull(dice, "dice must be set");
            if (!finishable) {
                OptionalInt stuck = BoardAnalysis.stuckSquare(board, finishRule, turnRules, dice.faces());
                if (stuck.isPresent()) {
                    String rules = finishRule + (turnRules.threeSixesForfeit() ? " with the three-sixes forfeit" : "");
                    throw new IllegalArgumentException(stuck.getAsInt() == 0
                            ? "nobody can ever reach square " + board.lastSquare() + " under " + rules
                            : "a player can get stuck forever on square " + stuck.getAsInt() + " under " + rules);
                }
                finishable = true;
            }
            return new Game(this);
        }
    }
}
