package com.lld.tictactoe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests for {@link GameConfig}: bad rules are refused up front, and the player list is copied. */
class GameConfigTest {

    private static final Player X = new Player("X", 'X');
    private static final Player O = new Player("O", 'O');

    /** Supplies the bad configs for the test below: too small, win length out of range, too few players, duplicate id, duplicate symbol. */
    static Stream<Arguments> invalidConfigs() {
        return Stream.of(
                Arguments.of("size 0", (Supplier<?>) () -> new GameConfig(0, 1, List.of(X, O))),
                Arguments.of("K > N", (Supplier<?>) () -> new GameConfig(3, 4, List.of(X, O))),
                Arguments.of("K 0", (Supplier<?>) () -> new GameConfig(3, 0, List.of(X, O))),
                Arguments.of("one player", (Supplier<?>) () -> new GameConfig(3, 3, List.of(X))),
                Arguments.of("duplicate id", (Supplier<?>) () -> new GameConfig(3, 3, List.of(X, new Player("X", 'Y')))),
                Arguments.of("duplicate symbol", (Supplier<?>) () -> new GameConfig(3, 3, List.of(X, new Player("Z", 'X')))),
                Arguments.of("blank id", (Supplier<?>) () -> new Player(" ", 'Q')),
                Arguments.of("empty-cell symbol", (Supplier<?>) () -> new Player("Q", Game.EMPTY)),
                Arguments.of("whitespace symbol", (Supplier<?>) () -> new Player("Q", ' ')));
    }

    /** Proves that every invalid config throws {@link IllegalArgumentException} when built, not later during play. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigs")
    void rejectsInvalidConfigsUpFront(String name, Supplier<?> build) {
        assertThrows(IllegalArgumentException.class, build::get);
    }

    /** Proves that changing the caller's player list after building the config does not change the config. */
    @Test
    void playersListIsSnapshotted() {
        List<Player> players = new ArrayList<>(List.of(X, O));
        GameConfig config = new GameConfig(3, 3, players);
        players.add(new Player("Z", 'Z'));

        assertEquals(2, config.players().size());
        assertThrows(UnsupportedOperationException.class, () -> config.players().add(X));
    }
}
