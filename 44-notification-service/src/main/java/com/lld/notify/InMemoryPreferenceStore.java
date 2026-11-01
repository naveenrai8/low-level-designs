package com.lld.notify;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A thread-safe in-memory {@link PreferenceStore} for tests and the demo app. */
public final class InMemoryPreferenceStore implements PreferenceStore {

    private final ConcurrentHashMap<String, UserPreferences> users = new ConcurrentHashMap<>();

    /** Stores (or replaces) the preferences of one user. */
    public void put(String userId, UserPreferences prefs) {
        users.put(userId, prefs);
    }

    /** Forgets a user, for example after the account is deleted. Messages still waiting for that user are then suppressed. */
    public void remove(String userId) {
        users.remove(userId);
    }

    /** Looks the user up. */
    @Override
    public Optional<UserPreferences> find(String userId) {
        return Optional.ofNullable(users.get(userId));
    }
}
