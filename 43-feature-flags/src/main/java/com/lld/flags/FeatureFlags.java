package com.lld.flags;

/**
 * The public face of the flag system: ask a question, or swap in a new config.
 * <p>Error contract: arguments must be non-null (a null flag key or context throws NullPointerException). {@link #evaluate} never throws for unknown flags or odd user data such as a missing attribute or a wrong type (it returns a fallback or a default with a reason). {@link #apply} throws nothing for a stale version (it returns STALE); an invalid config throws {@link InvalidConfigException} when you build it, and the live config is untouched.
 */
public interface FeatureFlags {

    /** What happened to a config offered to {@link #apply}. */
    enum ApplyResult {
        /** The config is now live. */ APPLIED,
        /** Its version was not above the live one, so it was ignored. */ STALE
    }

    /**
     * Decides which variant a user gets.
     * <p>The whole decision, including prerequisites, uses one snapshot.
     *
     * @param flagKey the flag
     * @param context the user
     * @param fallback returned (with reason FLAG_NOT_FOUND) when the flag does not exist
     * @return the variant with reason, rule id and config version
     */
    Evaluation evaluate(String flagKey, Context context, String fallback);

    /**
     * Swaps in a new config if its version is higher than the live one.
     *
     * @param config an already validated config
     * @return APPLIED or STALE
     */
    ApplyResult apply(FlagConfig config);

    /**
     * Says which config version is live.
     *
     * @return the version (0 before any config is applied)
     */
    long version();
}
