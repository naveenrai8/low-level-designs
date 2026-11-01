package com.lld.popularity;

/** Small input checks shared by the trackers. */
final class Validation {

    /** Not meant to be created. */
    private Validation() {
    }

    /** Throws {@link IllegalArgumentException} when an item or tenant id is null or blank. */
    static void item(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
    }
}
