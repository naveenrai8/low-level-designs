package com.lld.notify;

import java.util.Objects;

/**
 * SMS: plain text with a hard length limit.
 *
 * <p>Policy: a body longer than {@link #MAX_LENGTH} characters is cut and ends with "...", so the total is exactly the limit. We cut instead of rejecting because a clipped alert is better than no alert. The limit of 160 is the classic one for GSM-7 text; a message with emoji or accents switches to UCS-2 and the real limit drops to 70, which this simple version does not model.
 */
public final class SmsChannel implements Channel {

    /** The longest body we send, in characters. */
    public static final int MAX_LENGTH = 160;

    private final Transport transport;

    /** Creates an SMS channel that delivers through {@code transport}. */
    public SmsChannel(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /** Returns {@link ChannelType#SMS}. */
    @Override
    public ChannelType type() {
        return ChannelType.SMS;
    }

    /** SMS is plain text, so nothing needs escaping. */
    @Override
    public String escape(String value) {
        return value;
    }

    /** Returns the body unchanged if it fits, otherwise the first 157 characters plus "..." (156 if the 157th would cut an emoji in half). */
    @Override
    public String fit(String body) {
        if (body.length() <= MAX_LENGTH) {
            return body;
        }
        int cut = MAX_LENGTH - 3;
        if (Character.isHighSurrogate(body.charAt(cut - 1))) {
            cut--; // do not split an emoji in half: a lone surrogate is garbage on the phone
        }
        return body.substring(0, cut) + "...";
    }

    /** Hands the message to the transport. */
    @Override
    public void send(String address, String body, String idempotencyKey) {
        transport.deliver(address, body, idempotencyKey);
    }
}
