package com.lld.notify;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What one user wants.
 *
 * @param zone the user's time zone, used for quiet hours
 * @param addresses where to reach the user per channel (an email address, a phone number, a device token)
 * @param enabled the channels the user has opted in to; any channel not listed is opted out
 * @param quietHours the daily do-not-disturb window, or empty for none
 */
public record UserPreferences(ZoneId zone, Map<ChannelType, String> addresses, Set<ChannelType> enabled, Optional<QuietHours> quietHours) {

    /** Copies the collections so the preferences are immutable. */
    public UserPreferences {
        addresses = Map.copyOf(addresses);
        enabled = Set.copyOf(enabled);
    }

    /** Returns true if the user opted in to this channel. */
    public boolean allows(ChannelType channel) {
        return enabled.contains(channel);
    }
}
