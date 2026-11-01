package com.lld.notify;

/** The ways a notification can reach a person. Each one has its own {@link Channel} (Strategy) that knows how to format and send. */
public enum ChannelType {
    /** An email. The body is HTML, so values put into it are HTML-escaped. */
    EMAIL,
    /** A text message. Short: the body is cut to 160 characters. */
    SMS,
    /** A phone push notification. Plain text. */
    PUSH
}
