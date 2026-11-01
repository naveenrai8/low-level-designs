package com.lld.parkinglot;

import java.time.Instant;

/**
 * What the driver gets when leaving.
 *
 * @param ticket the ticket that was handed back
 * @param exit when the vehicle left, read from the injected clock
 * @param fee the price in minor units (for example cents), never a double
 */
public record Receipt(Ticket ticket, Instant exit, long fee) {
}
