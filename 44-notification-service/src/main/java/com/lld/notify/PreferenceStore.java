package com.lld.notify;

import java.util.Optional;

/** Where user preferences are looked up. In production this is a database or the user service; the core only needs this one question. */
public interface PreferenceStore {

    /** Returns the preferences, or empty if the user is unknown. It is read again on every send attempt, so a change of mind takes effect on messages still waiting. */
    Optional<UserPreferences> find(String userId);
}
