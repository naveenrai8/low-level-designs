package com.lld.notify;

import java.util.Objects;

/** Email: the body is HTML, so every value is HTML-escaped before it goes into the template. */
public final class EmailChannel implements Channel {

    private final Transport transport;

    /** Creates an email channel that delivers through {@code transport}. */
    public EmailChannel(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** Returns {@link ChannelType#EMAIL}. */
    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    /** Escapes the five HTML special characters. The ampersand goes first, otherwise the {@code &} we add for the others would be escaped again. */
    @Override
    public String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Email has no practical length limit here, so the body is unchanged. */
    @Override
    public String fit(String body) {
        return body;
    }

    /** Hands the message to the transport. */
    @Override
    public void send(String address, String body, String idempotencyKey) {
        transport.deliver(address, body, idempotencyKey);
    }
}
