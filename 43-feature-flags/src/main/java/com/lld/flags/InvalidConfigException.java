package com.lld.flags;

/**
 * Thrown when a flag or a whole config breaks a rule, for example a prerequisite cycle or weights that do not add up.
 * <p>It is thrown while a config is being built, so a bad config can never become the live one.
 */
public final class InvalidConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message that names the broken rule.
     *
     * @param message what is wrong and where
     */
    public InvalidConfigException(String message) {
        super(message);
    }
}
