package com.lld.snakeladder;

import java.util.Objects;

/** A player, known by name. */
public record Player(String name) {

    /** Checks the name is not null. */
    public Player {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("player name must not be blank");
        }
    }
}
