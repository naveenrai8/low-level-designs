package com.lld.notify;

import java.util.Objects;

/** Push: plain text shown on a phone. The provider limits real payloads to a few KB; this simple version does not model that. */
public final class PushChannel implements Channel {

    private final Transport transport;

    /** Creates a push channel that delivers through {@code transport}. */
    public PushChannel(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** Returns {@link ChannelType#PUSH}. */
    @Override
    public ChannelType type() {
        return ChannelType.PUSH;
    }

    /** Push is plain text, so nothing needs escaping. */
    @Override
    public String escape(String value) {
        return value;
    }

    /** The body is unchanged. */
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
