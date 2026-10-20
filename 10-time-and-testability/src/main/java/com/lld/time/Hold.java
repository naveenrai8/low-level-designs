package com.lld.time;

import java.time.Instant;

/**
 * A temporary claim on a seat. Valid over the half-open interval {@code [createdAt, expiresAt)}:
 * at exactly {@code expiresAt} it is gone. Records compare by value, so a hold's identity is its
 * {@code id} plus fields, which is what lets a stale timer tell "my hold" from "the next user's
 * hold on the same seat".
 */
public record Hold(long id, String seat, String user, Instant createdAt, Instant expiresAt) { }
