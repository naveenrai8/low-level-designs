package com.lld.vending;

/**
 * A deliberately broken payment desk, kept only to prove the bug class: it checks "is there still room before the price?" and then adds the coin as two separate steps, with no lock around both.
 *
 * <p>A test hook sits in the gap between the check and the write, so a test can park two threads there and force the bad interleaving every time. Two customers each see room, both add, and the balance ends above the price (overpay).
 */
final class CheckThenActPaymentDesk {

    private final long price;
    private volatile long balance;
    private final Runnable gap;

    /** Creates a desk for one price with some money already in; {@code gap} runs between the check and the write. */
    CheckThenActPaymentDesk(long price, long startBalance, Runnable gap) {
        this.price = price;
        this.balance = startBalance;
        this.gap = gap;
    }

    /** Adds a coin (the write itself is safe, so the only bug left is the check) if the price is not yet covered. The check and the write are not one atomic step, which is the bug. */
    boolean insert(long coin) {
        if (balance >= price) {
            return false;
        }
        gap.run();
        synchronized (this) {
            balance += coin;
        }
        return true;
    }

    /** Returns the money collected so far. */
    long balance() {
        return balance;
    }
}
