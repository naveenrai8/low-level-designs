package com.lld.vending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Behaviour of the whole machine: the happy path, refunds, no-change refusal and jams. */
class VendingMachineTest {

    /** Full happy path: 125 price, pay 100+100, get 25+25+25 back, stock drops by one, money balances. */
    @Test
    void sellsAndReturnsChange() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.loadCoins(25, 3);
        Payout boxBefore = f.machine.coinBox();
        f.machine.select("A1");
        f.insertAll(100, 100);
        assertEquals(State.PAID, f.machine.state());
        SaleResult r = f.machine.dispense();
        assertEquals(SaleResult.Status.SOLD, r.status());
        assertEquals("A1", r.productCode());
        assertEquals(new Payout(Map.of(25L, 3)), r.returned());
        assertEquals(125, r.kept());
        assertEquals(200, r.inserted());
        // box = before + session coins - change: three 25s + two 100s - three 25s
        assertEquals(new Payout(Map.of(100L, 2)), f.machine.coinBox());
        assertEquals(boxBefore.total() + 200 - r.returned().total(), f.machine.coinBox().total());
        assertEquals(2, f.shelf.count("A1"));
        assertEquals(State.IDLE, f.machine.state());
        assertEquals(0, f.machine.balance());
        assertEquals(List.of(MachineEvent.Kind.SALE), f.events.stream().map(MachineEvent::kind).toList());
    }

    /** The state moves SELECTING until the price is covered, and only then PAID. */
    @Test
    void becomesPaidExactlyWhenPriceIsCovered() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.select("A1");
        f.machine.insert(100);
        assertEquals(State.SELECTING, f.machine.state());
        f.machine.insert(25);
        assertEquals(State.PAID, f.machine.state());
    }

    /** Cancel gives back the exact coins that went in. */
    @Test
    void cancelRefundsExactCoins() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.select("A1");
        f.insertAll(100, 10, 10);
        Payout back = f.machine.cancel();
        assertEquals(new Payout(Map.of(100L, 1, 10L, 2)), back);
        assertEquals(State.IDLE, f.machine.state());
        assertEquals(new Payout(Map.of()), f.machine.coinBox());
    }

    /** No exact change: the machine refuses before dispensing, returns every inserted coin, and the stock is untouched. */
    @Test
    void refusesWhenChangeCannotBeMade() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.select("A1");
        f.insertAll(100, 100);
        SaleResult r = f.machine.dispense();
        assertEquals(SaleResult.Status.REFUSED_NO_CHANGE, r.status());
        assertNull(r.productCode());
        assertEquals(new Payout(Map.of(100L, 2)), r.returned());
        assertEquals(0, r.kept());
        assertEquals(200, r.inserted());
        assertEquals(3, f.shelf.count("A1"));
        assertEquals(new Payout(Map.of()), f.machine.coinBox());
        assertEquals(State.IDLE, f.machine.state());
        assertEquals(List.of(MachineEvent.Kind.REFUSED_NO_CHANGE), f.events.stream().map(MachineEvent::kind).toList());
    }

    /** The refusal never calls the dispenser, so the product was never touched. */
    @Test
    void refusalNeverTouchesTheDispenser() {
        int[] calls = {0};
        Fixtures f = new Fixtures(p -> calls[0]++);
        f.machine.select("A1");
        f.insertAll(100, 100);
        f.machine.dispense();
        assertEquals(0, calls[0]);
    }

    /** Change can come from the customer's own coins: pay 100+10+10+25 for 125, and the 20 change is the two 10s they just inserted. */
    @Test
    void usesInsertedCoinsAsChangeToo() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.select("A1");
        f.insertAll(100, 10, 10, 25);
        SaleResult r = f.machine.dispense();
        assertEquals(SaleResult.Status.SOLD, r.status());
        assertEquals(new Payout(Map.of(10L, 2)), r.returned());
        assertEquals(145, r.inserted());
    }

    /** A jam refunds every coin, keeps nothing, leaves stock alone and the box unchanged: inserted = refunded + kept exactly. */
    @Test
    void jamRefundsEverythingAndConservesMoney() {
        Fixtures f = new Fixtures(Fixtures.jamming());
        f.machine.loadCoins(25, 3);
        Payout boxBefore = f.machine.coinBox();
        f.machine.select("A1");
        f.insertAll(100, 100);
        SaleResult r = f.machine.dispense();
        assertEquals(SaleResult.Status.JAMMED_REFUNDED, r.status());
        assertEquals(new Payout(Map.of(100L, 2)), r.returned());
        assertEquals(0, r.kept());
        assertEquals(200, r.inserted());
        assertEquals(3, f.shelf.count("A1"));
        assertEquals(boxBefore, f.machine.coinBox());
        assertEquals(State.IDLE, f.machine.state());
        assertEquals(200, f.events.get(0).amount());
        assertEquals(MachineEvent.Kind.JAM, f.events.get(0).kind());
    }

    /** Any unexpected hardware exception is treated as a jam and also refunds. */
    @Test
    void unexpectedDispenserFailureAlsoRefunds() {
        Fixtures f = new Fixtures(p -> {
            throw new IllegalStateException("sensor exploded");
        });
        f.machine.loadCoins(25, 3);
        f.machine.select("A1");
        f.insertAll(100, 100);
        SaleResult r = f.machine.dispense();
        assertEquals(SaleResult.Status.JAMMED_REFUNDED, r.status());
        assertEquals(200, r.returned().total());
        assertEquals(3, f.shelf.count("A1"));
    }

    /** After a jam the machine is usable again for a normal sale. */
    @Test
    void machineRecoversAfterJam() {
        boolean[] jam = {true};
        Fixtures f = new Fixtures(p -> {
            if (jam[0]) {
                throw new DispenseJamException("once");
            }
        });
        f.machine.select("A1");
        f.insertAll(100, 25);
        f.machine.dispense();
        jam[0] = false;
        f.machine.select("A1");
        f.insertAll(100, 25);
        assertEquals(SaleResult.Status.SOLD, f.machine.dispense().status());
        assertEquals(2, f.shelf.count("A1"));
    }

    /** An operator sets stock to 0 after the customer selected: dispense refuses before the motor, refunds everything and the machine is not stuck. */
    @Test
    void restockToZeroBetweenSelectAndDispenseRefunds() {
        int[] calls = {0};
        Fixtures f = new Fixtures(p -> calls[0]++);
        f.machine.loadCoins(25, 3);
        f.machine.select("A1");
        f.insertAll(100, 100);
        f.shelf.load("A1", 0);
        SaleResult r = f.machine.dispense();
        assertEquals(SaleResult.Status.REFUSED_OUT_OF_STOCK, r.status());
        assertEquals(new Payout(Map.of(100L, 2)), r.returned());
        assertEquals(0, calls[0]);
        assertEquals(State.IDLE, f.machine.state());
        assertEquals(new Payout(Map.of(25L, 3)), f.machine.coinBox());
        f.shelf.load("A1", 1);
        f.machine.select("A1");
    }

    /** If the stock bookkeeping fails after the product dropped, the sale still stands, the money moves and the machine returns to IDLE. */
    @Test
    void stockFaultAfterDropStillCompletesTheSale() {
        Inventory flaky = new Inventory() {
            /** Always says yes. */
            @Override
            public boolean inStock(String code) {
                return true;
            }

            /** Always fails, like a broken counter. */
            @Override
            public void take(String code) {
                throw new IllegalStateException("counter broke");
            }
        };
        List<MachineEvent> events = new java.util.ArrayList<>();
        DefaultVendingMachine m = new DefaultVendingMachine(List.of(Fixtures.COLA), java.util.Set.of(100L, 25L), flaky, Fixtures.working(), events::add, Fixtures.CLOCK);
        m.loadCoins(25, 3);
        m.select("A1");
        m.insert(100);
        m.insert(100);
        SaleResult r = m.dispense();
        assertEquals(SaleResult.Status.SOLD, r.status());
        assertEquals(State.IDLE, m.state());
        assertEquals(new Payout(Map.of(100L, 2)), m.coinBox());
        assertEquals(MachineEvent.Kind.STOCK_FAULT, events.get(0).kind());
    }

    /** Even an Error from the hardware ends in a refund and IDLE, never a stuck DISPENSING. */
    @Test
    void hardwareErrorRefundsAndDoesNotStickInDispensing() {
        Fixtures f = new Fixtures(p -> {
            throw new AssertionError("firmware panic");
        });
        f.machine.loadCoins(25, 3);
        f.machine.select("A1");
        f.insertAll(100, 100);
        assertEquals(SaleResult.Status.JAMMED_REFUNDED, f.machine.dispense().status());
        assertEquals(State.IDLE, f.machine.state());
    }

    /** An operator adds coins while the motor runs (same-thread probe): the coins must still be in the box after the sale. */
    @Test
    void loadCoinsDuringDispensingIsNotLost() {
        Fixtures[] holder = new Fixtures[1];
        holder[0] = new Fixtures(p -> holder[0].machine.loadCoins(5, 7));
        holder[0].machine.loadCoins(25, 3);
        holder[0].machine.select("A1");
        holder[0].insertAll(100, 100);
        holder[0].machine.dispense();
        assertEquals(new Payout(Map.of(100L, 2, 5L, 7)), holder[0].machine.coinBox());
    }

    /** A throwing telemetry also cannot break a jam refund or a no-change refusal. */
    @Test
    void brokenTelemetryDoesNotBreakRefunds() {
        ShelfInventory shelf = new ShelfInventory();
        shelf.load("A1", 1);
        Telemetry broken = e -> {
            throw new IllegalStateException("network down");
        };
        DefaultVendingMachine jam = new DefaultVendingMachine(List.of(Fixtures.COLA), java.util.Set.of(100L, 25L), shelf, Fixtures.jamming(), broken, Fixtures.CLOCK);
        jam.loadCoins(25, 3);
        jam.select("A1");
        jam.insert(100);
        jam.insert(100);
        assertEquals(SaleResult.Status.JAMMED_REFUNDED, jam.dispense().status());
        DefaultVendingMachine empty = new DefaultVendingMachine(List.of(Fixtures.COLA), java.util.Set.of(100L, 25L), shelf, Fixtures.working(), broken, Fixtures.CLOCK);
        empty.select("A1");
        empty.insert(100);
        empty.insert(100);
        assertEquals(SaleResult.Status.REFUSED_NO_CHANGE, empty.dispense().status());
        assertEquals(State.IDLE, empty.state());
    }

    /** Selecting a sold-out or unknown product fails without moving the machine. */
    @Test
    void soldOutAndUnknownProductsAreRejected() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.shelf.load("A1", 0);
        assertThrows(SoldOutException.class, () -> f.machine.select("A1"));
        assertThrows(IllegalArgumentException.class, () -> f.machine.select("ZZ"));
        assertEquals(State.IDLE, f.machine.state());
    }

    /** A coin the machine does not take is rejected and the balance is unchanged. */
    @Test
    void unknownCoinIsRejected() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.select("A1");
        assertThrows(IllegalArgumentException.class, () -> f.machine.insert(7));
        assertEquals(0, f.machine.balance());
    }

    /** The last unit sells once; a second customer then finds it sold out. */
    @Test
    void lastUnitSellsOnce() {
        Fixtures f = new Fixtures(Fixtures.working());
        f.shelf.load("A1", 1);
        f.machine.select("A1");
        f.insertAll(100, 25);
        f.machine.dispense();
        assertThrows(SoldOutException.class, () -> f.machine.select("A1"));
    }

    /** A telemetry that throws must not break the sale or the refund. */
    @Test
    void brokenTelemetryDoesNotBreakASale() {
        ShelfInventory shelf = new ShelfInventory();
        shelf.load("A1", 1);
        DefaultVendingMachine m = new DefaultVendingMachine(List.of(Fixtures.COLA), java.util.Set.of(100L, 25L), shelf, Fixtures.working(), e -> {
            throw new IllegalStateException("network down");
        }, Fixtures.CLOCK);
        m.select("A1");
        m.insert(100);
        m.insert(25);
        assertEquals(SaleResult.Status.SOLD, m.dispense().status());
    }
}
