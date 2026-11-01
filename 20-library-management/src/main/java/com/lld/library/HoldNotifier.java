package com.lld.library;

/** The port the library uses to tell a member their held copy is ready. Email, SMS or a queue sit behind it. */
@FunctionalInterface
public interface HoldNotifier {

    /** Tells the member in the notice. May throw: the library catches it, counts it, and carries on, because a broken email server must not block a return. */
    void holdReady(HoldNotice notice);

    /** A notifier that does nothing. */
    HoldNotifier NONE = notice -> { };
}
