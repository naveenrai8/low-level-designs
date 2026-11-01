package com.lld.resilience;

/**
 * A limit on retries as a fraction of recent requests, so a retry storm cannot happen.
 *
 * <p>Think of a jar of tokens. Every <em>request</em> drops a small amount into the jar (for example 0.1 token). Every <em>retry</em> must take out one whole token, and if the jar has less than one, the retry is refused. With a 10 percent ratio, 100 requests earn exactly 10 retries however badly the dependency behaves. The jar holds at most {@code maxTokens}, so a long quiet period cannot bank a huge number of retries for later: that is what makes the fraction apply to <em>recent</em> traffic.
 *
 * <p>Amounts are kept in thousandths of a token (whole numbers), so the count is exact and there is no floating point.
 */
public final class RetryBudget {

    private static final long TOKEN = 1000;

    private final boolean unlimited;
    private final long permillePerRequest;
    private final long maxMilli;
    private long milli;

    /**
     * Creates a budget.
     *
     * @param ratioPermille thousandths of a retry earned per request: 100 means one retry per ten requests
     * @param maxTokens the most whole retries the jar can hold
     */
    public RetryBudget(int ratioPermille, int maxTokens) {
        if (ratioPermille < 0 || ratioPermille > 1000 || maxTokens < 1) {
            throw new IllegalArgumentException("ratioPermille must be 0..1000 and maxTokens at least 1");
        }
        this.unlimited = false;
        this.permillePerRequest = ratioPermille;
        this.maxMilli = maxTokens * TOKEN;
    }

    /** Creates the no-op budget used when no budget is configured. */
    private RetryBudget() {
        this.unlimited = true;
        this.permillePerRequest = 0;
        this.maxMilli = 0;
    }

    /** Returns a budget that never refuses a retry. */
    public static RetryBudget unlimited() {
        return new RetryBudget();
    }

    /** Records one new request (not a retry) and earns its share of a token. */
    public synchronized void onRequest() {
        milli = Math.min(maxMilli, milli + permillePerRequest);
    }

    /** Tries to pay for one retry; returns false (and takes nothing) when the jar holds less than one whole token. */
    public synchronized boolean tryRetry() {
        if (unlimited) {
            return true;
        }
        if (milli < TOKEN) {
            return false;
        }
        milli -= TOKEN;
        return true;
    }

    /** Returns the whole retries the jar holds now. */
    public synchronized long available() {
        return milli / TOKEN;
    }
}
