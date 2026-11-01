package com.lld.notify;

/** The last step of a send: hands one finished message to the outside provider (an SMTP server, an SMS gateway). Tests and the demo app plug in fakes. */
@FunctionalInterface
public interface Transport {

    /**
     * Delivers one message.
     *
     * <p>The {@code idempotencyKey} is the notification id. A real provider that supports one drops a second delivery with the same key, which is what makes a resend after a crash safe.
     *
     * @throws ChannelException with {@link ChannelException#isTransient()} true if trying again may work (timeout, 503), false if it never will (bad address)
     */
    void deliver(String address, String body, String idempotencyKey);
}
