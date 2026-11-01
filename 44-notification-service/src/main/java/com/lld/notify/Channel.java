package com.lld.notify;

/**
 * One way to reach a person (the Strategy pattern: the service picks a channel and never cares which one it is).
 *
 * <p>A channel knows three things that differ per medium: how to make a value safe inside its body ({@link #escape}), how to make the body fit ({@link #fit}), and how to send it ({@link #send}).
 */
public interface Channel {

    /** Which medium this channel is. The service uses it to find the right strategy. */
    ChannelType type();

    /** Makes one template value safe for this medium's body. Email turns {@code <} into {@code &lt;} so a user name cannot inject HTML. SMS and push do nothing. */
    String escape(String value);

    /** Makes the finished body fit this medium (SMS cuts it to its length limit). Email and push return it unchanged. */
    String fit(String body);

    /** Sends one finished body to {@code address}. Throws {@link ChannelException} on failure. */
    void send(String address, String body, String idempotencyKey);
}
