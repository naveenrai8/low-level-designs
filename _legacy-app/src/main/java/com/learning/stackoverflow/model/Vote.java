package com.learning.stackoverflow.model;

import java.time.Instant;

public record Vote(
    String id,
    Long userId,
    Long targetId,
    VoteType targetType,
    VoteValue value,
    Instant createdAt
) {
    public static Vote create(Long userId, Long targetId, VoteType type, VoteValue value) {
        String id = userId + ":" + targetId + ":" + type;
        return new Vote(id, userId, targetId, type, value, Instant.now());
    }
}
