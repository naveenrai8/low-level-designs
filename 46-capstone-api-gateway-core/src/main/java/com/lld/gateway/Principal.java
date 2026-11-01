package com.lld.gateway;

import java.util.Set;

/**
 * Who is calling, as decided by the auth hook.
 *
 * @param id a stable client id; it is also the rate-limit key, so it must not be the secret API key itself
 * @param scopes the permissions this client holds, for example {@code orders:read}
 */
public record Principal(String id, Set<String> scopes) {

    /** Checks the id (it becomes part of the rate-limit key) and makes the scope set unmodifiable. */
    public Principal {
        if (id == null || id.isBlank() || id.contains("|")) {
            throw new IllegalArgumentException("a client id must be non-blank and may not contain '|' (it is part of the rate-limit key)");
        }
        scopes = Set.copyOf(scopes);
    }
}
