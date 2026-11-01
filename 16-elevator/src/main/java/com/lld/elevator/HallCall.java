package com.lld.elevator;

/** A press of an up or down button on a floor. Two presses of the same button are the same call. */
public record HallCall(int floor, Direction direction) {
}
