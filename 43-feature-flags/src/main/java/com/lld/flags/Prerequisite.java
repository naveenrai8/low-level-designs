package com.lld.flags;

/**
 * A dependency: this flag only runs its normal logic if another flag currently serves a given variant to the same user.
 *
 * @param flagKey the other flag
 * @param variant the variant that other flag must serve
 */
public record Prerequisite(String flagKey, String variant) {

    /**
     * Rejects blank names.
     */
    public Prerequisite {
        if (flagKey == null || flagKey.isBlank() || variant == null || variant.isBlank()) {
            throw new InvalidConfigException("prerequisite needs a flag key and a variant");
        }
    }
}
