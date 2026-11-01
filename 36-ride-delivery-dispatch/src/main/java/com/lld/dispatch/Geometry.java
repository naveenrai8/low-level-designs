package com.lld.dispatch;

/** Small integer maths helpers, so no comparison ever needs a double. */
public final class Geometry {

    /** Not instantiable: static helpers only. */
    private Geometry() {
    }

    /**
     * The smallest whole number r with r*r at least n, found with integer Newton steps. Used to turn a squared distance into whole metres for pricing and reports.
     *
     * @param n a non-negative squared value
     * @return ceil(sqrt(n))
     */
    public static long isqrtCeil(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative");
        }
        if (n < 2) {
            return n;
        }
        long x = n;
        long y = x / 2 + (x & 1); // ceil(x/2) without the overflow of (x + 1) / 2
        while (y < x) {
            x = y;
            y = (x + n / x) / 2;
        }
        // x is floor(sqrt(n)) here; round up when n is not a perfect square
        return x * x == n ? x : x + 1;
    }
}
