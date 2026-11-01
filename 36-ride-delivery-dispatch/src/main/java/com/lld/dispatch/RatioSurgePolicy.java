package com.lld.dispatch;

/**
 * Simple policy: no surge while drivers cover the requests; otherwise add {@code slopeBps} for every 100 percent of shortfall, up to a cap.
 * <p>bps = min(cap, 10,000 + (open - available) * slopeBps / max(available, 1)) when open exceeds available. Integer division rounds down, which keeps the curve non-decreasing in open and non-increasing in available.
 */
public final class RatioSurgePolicy implements PricingPolicy {

    private final int slopeBps;
    private final int capBps;

    /**
     * Builds the policy.
     *
     * @param slopeBps extra bps per 100 percent shortfall, at least 0
     * @param capBps the highest multiplier, at least 10,000
     */
    public RatioSurgePolicy(int slopeBps, int capBps) {
        if (slopeBps < 0 || capBps < 10_000) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "slope " + slopeBps + " cap " + capBps);
        }
        this.slopeBps = slopeBps;
        this.capBps = capBps;
    }

    /** Applies the formula, then clamps to the cap. */
    @Override
    public int surgeBps(int openRequests, int availableDrivers) {
        if (openRequests < 0 || availableDrivers < 0) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "counts " + openRequests + "," + availableDrivers);
        }
        if (openRequests <= availableDrivers) {
            return 10_000;
        }
        long extra = (long) (openRequests - availableDrivers) * slopeBps / Math.max(availableDrivers, 1);
        return (int) Math.min(capBps, 10_000L + extra);
    }
}
