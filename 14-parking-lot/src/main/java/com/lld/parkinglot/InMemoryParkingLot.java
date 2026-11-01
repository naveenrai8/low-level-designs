package com.lld.parkinglot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The in-memory lot. Time comes from the injected {@link Clock}, prices from the injected {@link PricingStrategy}.
 *
 * <p>Concurrency model: the only shared thing two gates fight over is a spot. Each spot holds one {@link AtomicReference}: {@code null} (free), a {@link Reservation} (held) or a ticket id string (taken). A gate takes a spot with one compare-and-set from the value it saw to its own value, so exactly one of two racing gates wins and the loser moves to the next spot. No lock is held while searching, so gates only collide on the same spot.
 *
 * <p>Every path that changes a spot uses compare-and-set against the value it saw, never a plain set, because a plain set can overwrite a spot another gate just won. A clock reading is never the final word on expiry: the spot's compare-and-set is.
 *
 * <p>A plate is claimed in a second map with {@code putIfAbsent} before the spot search, so the same vehicle cannot take two spots even if two gates see it at once.
 */
public final class InMemoryParkingLot implements ParkingLot {

    /** One physical spot. Its state is the single place the "is it free" decision is made. */
    private static final class Spot {
        private final String id;
        private final SpotType type;
        private final AtomicReference<Object> state = new AtomicReference<>();

        /** Creates a free spot. */
        Spot(String id, SpotType type) {
            this.id = id;
            this.type = type;
        }

        /** True if nobody has it: empty, or held by a reservation that has run out at {@code now}. */
        boolean isFreeAt(Object seen, Instant now) {
            return seen == null || (seen instanceof Reservation r && !r.validAt(now));
        }
    }

    private final List<Spot> spots = new ArrayList<>();
    private final Map<String, Spot> spotById = new ConcurrentHashMap<>();
    private final Map<String, Ticket> liveTickets = new ConcurrentHashMap<>();
    private final Set<String> usedTickets = ConcurrentHashMap.newKeySet();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Set<String> usedReservations = ConcurrentHashMap.newKeySet();
    private final Map<String, String> plates = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final PricingStrategy pricing;
    private final Clock clock;
    private final ClaimHook hook;

    /**
     * Builds the lot from a layout: one map per level, from spot size to how many spots of that size.
     *
     * @param levels one entry per level, in order; the lowest level is searched first
     * @param pricing how a stay is priced
     * @param clock the source of "now" for entry, exit and hold expiry
     * @param hook called just before a gate tries to take a spot; use {@link ClaimHook#NONE} in production
     */
    public InMemoryParkingLot(List<Map<SpotType, Integer>> levels, PricingStrategy pricing, Clock clock, ClaimHook hook) {
        this.pricing = Objects.requireNonNull(pricing);
        this.clock = Objects.requireNonNull(clock);
        this.hook = Objects.requireNonNull(hook);
        for (int level = 0; level < levels.size(); level++) {
            for (SpotType type : SpotType.values()) {
                int count = levels.get(level).getOrDefault(type, 0);
                for (int n = 0; n < count; n++) {
                    Spot spot = new Spot("L" + level + "-" + type + "-" + n, type);
                    spots.add(spot);
                    spotById.put(spot.id, spot);
                }
            }
        }
    }

    /** Convenience constructor with no test hook. */
    public InMemoryParkingLot(List<Map<SpotType, Integer>> levels, PricingStrategy pricing, Clock clock) {
        this(levels, pricing, clock, ClaimHook.NONE);
    }

    /** {@inheritDoc} */
    @Override
    public Ticket park(Vehicle vehicle) {
        Instant now = clock.instant();
        String ticketId = "T" + ids.incrementAndGet();
        claimPlate(vehicle.plate(), ticketId, now);
        try {
            Claim claim = claimSpot(vehicle.type(), spot -> ticketId, now);
            Ticket ticket = new Ticket(ticketId, vehicle, claim.spot().id, now);
            liveTickets.put(ticketId, ticket);
            return ticket;
        } catch (RuntimeException e) {
            plates.remove(vehicle.plate(), ticketId);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Trade-off: the fee is worked out before the ticket is marked used. If pricing throws, the ticket is untouched and still works. If two gates unpark the same ticket at once, both price it, but only one wins {@code usedTickets.add} and the other gets {@code TicketAlreadyUsed}, so "already used" is never reported for a ticket that is still valid.
     */
    @Override
    public Receipt unpark(String ticketId) {
        Ticket ticket = liveTickets.get(ticketId);
        if (ticket == null) {
            throw usedTickets.contains(ticketId) ? new ParkingException.TicketAlreadyUsed(ticketId) : new ParkingException.UnknownTicket(ticketId);
        }
        Instant exit = clock.instant();
        long fee = pricing.fee(ticket.vehicle().type(), ticket.entry(), exit);
        // Only one caller can add the id to the used set, so only one caller leaves with this ticket.
        if (!usedTickets.add(ticketId)) {
            throw new ParkingException.TicketAlreadyUsed(ticketId);
        }
        liveTickets.remove(ticketId);
        // Free the spot only if it still holds this ticket, so a stale call can never free someone else's spot.
        releaseIfOwnedBy(spotById.get(ticket.spotId()), ticketId);
        plates.remove(ticket.vehicle().plate(), ticketId);
        return new Receipt(ticket, exit, fee);
    }

    /** {@inheritDoc} */
    @Override
    public Reservation reserve(Vehicle vehicle, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        Instant now = clock.instant();
        String id = "R" + ids.incrementAndGet();
        claimPlate(vehicle.plate(), id, now);
        try {
            // The reservation already knows its spot, so one compare-and-set both takes the spot and stores the hold.
            Claim claim = claimSpot(vehicle.type(), spot -> new Reservation(id, vehicle, spot.id, now.plus(ttl)), now);
            Reservation held = (Reservation) claim.owner();
            reservations.put(id, held);
            return held;
        } catch (RuntimeException e) {
            plates.remove(vehicle.plate(), id);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The clock check is only a quick exit. The decision is the compare-and-set from the reservation to the ticket: if another gate took the spot after the hold ran out, that CAS fails and this call throws {@code ReservationExpired}, even when this gate's own clock reading was still inside the hold.
     */
    @Override
    public Ticket parkReserved(String reservationId) {
        Reservation r = reservations.get(reservationId);
        if (r == null) {
            throw new ParkingException.UnknownReservation(reservationId);
        }
        Instant now = clock.instant();
        if (!r.validAt(now)) {
            expireHold(r);
            throw new ParkingException.ReservationExpired(reservationId);
        }
        hook.afterHoldCheck(reservationId);
        String ticketId = "T" + ids.incrementAndGet();
        Spot spot = spotById.get(r.spotId());
        if (!spot.state.compareAndSet(r, ticketId)) {
            // The spot is no longer this hold: another gate took it after expiry, or this reservation was already used.
            throw reservations.containsKey(reservationId) || !usedReservations.contains(reservationId)
                    ? new ParkingException.ReservationExpired(reservationId)
                    : new ParkingException.UnknownReservation(reservationId);
        }
        usedReservations.add(reservationId);
        reservations.remove(reservationId, r);
        plates.replace(r.vehicle().plate(), reservationId, ticketId);
        Ticket ticket = new Ticket(ticketId, r.vehicle(), spot.id, now);
        liveTickets.put(ticketId, ticket);
        return ticket;
    }

    /** {@inheritDoc} */
    @Override
    public int freeSpots(SpotType type) {
        Instant now = clock.instant();
        int free = 0;
        for (Spot s : spots) {
            if (s.type == type && s.isFreeAt(s.state.get(), now)) {
                free++;
            }
        }
        return free;
    }

    /** Registers the plate under {@code owner} (a ticket or reservation id) or throws if the plate is already in use. A plate whose old hold has run out is released first. */
    private void claimPlate(String plate, String owner, Instant now) {
        while (true) {
            String existing = plates.putIfAbsent(plate, owner);
            if (existing == null) {
                return;
            }
            Reservation old = reservations.get(existing);
            if (old != null && !old.validAt(now)) {
                expireHold(old);
                continue;
            }
            throw new ParkingException.DuplicateVehicle(plate);
        }
    }

    /** Releases an expired hold: frees its spot with a compare-and-set (so it only works if the hold is still there), then drops the reservation and the plate claim. If another gate already took the spot, it cleans up the same way, so this is safe to repeat. */
    private void expireHold(Reservation old) {
        spotById.get(old.spotId()).state.compareAndSet(old, null);
        reservations.remove(old.id(), old);
        plates.remove(old.vehicle().plate(), old.id());
    }

    /** Frees the spot only if it still holds this ticket id. Compare-and-set compares references, so we read the stored value first and pass that exact object back. */
    private static void releaseIfOwnedBy(Spot spot, String ticketId) {
        Object seen = spot.state.get();
        if (ticketId.equals(seen)) {
            spot.state.compareAndSet(seen, null);
        }
    }

    /** The spot a gate won and the value it stored there. */
    private record Claim(Spot spot, Object owner) {
    }

    /**
     * Finds the smallest fitting spot and takes it with one compare-and-set. {@code ownerFor} builds the value to store (a ticket id or a {@link Reservation}) for the spot being tried. Loses gracefully: if another gate wins a spot first, this gate moves on to the next one. Throws {@code LotFull} when none is left.
     */
    private Claim claimSpot(VehicleType vehicle, Function<Spot, Object> ownerFor, Instant now) {
        for (SpotType size : SpotType.values()) {
            if (!fits(vehicle, size)) {
                continue;
            }
            for (Spot spot : spots) {
                if (spot.type != size) {
                    continue;
                }
                Object seen = spot.state.get();
                if (!spot.isFreeAt(seen, now)) {
                    continue;
                }
                hook.beforeClaim(spot.id);
                Object owner = ownerFor.apply(spot);
                if (spot.state.compareAndSet(seen, owner)) {
                    if (seen instanceof Reservation old) {
                        // The old hold ran out, so its owner loses the spot and the plate claim.
                        reservations.remove(old.id(), old);
                        plates.remove(old.vehicle().plate(), old.id());
                    }
                    return new Claim(spot, owner);
                }
            }
        }
        throw new ParkingException.LotFull(vehicle);
    }

    /** Says whether a vehicle of this type may use a spot of this size. */
    private static boolean fits(VehicleType vehicle, SpotType spot) {
        return switch (vehicle) {
            case MOTORCYCLE -> true;
            case CAR -> spot != SpotType.SMALL;
            case TRUCK -> spot == SpotType.LARGE;
        };
    }
}
