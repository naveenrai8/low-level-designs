package com.lld.elevator;

/** A read-only snapshot of one car, handed to dispatchers. It is a copy, so a dispatcher cannot change a car. */
public record CarView(int id, int floor, Direction direction, int pending) {
}
