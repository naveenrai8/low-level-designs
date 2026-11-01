package com.lld.mistakes;

/**
 * <b>Bug (M6).</b> Money as {@code double}, rounded per share.
 * <p>Example: $100.00 split three ways gives 33.33 + 33.33 + 33.33 = 99.99, so a cent disappears. $20.00 gives 20.01, so a cent appears.
 * <p>The old reviews' conservation test asserted {@code assertEquals(expected, actual, 0.01)}. A one-cent error lands just under or just over 0.01 in binary floating point, so that test catches the $100 leak and misses the $10 one. Measured in {@code MoneySplitTest}.
 */
public final class DoubleSplit {

    /**
     * Not instantiable. This is a static helper only.
     */
    private DoubleSplit() { }

    /**
     * Splits the total into equal shares, rounding each share to cents on its own. The shares may not add back up to the total.
     */
    public static double[] split(double total, int parts) {
        double[] shares = new double[parts];
        for (int i = 0; i < parts; i++) {
            shares[i] = Math.round(total / parts * 100.0) / 100.0;
        }
        return shares;
    }
}
