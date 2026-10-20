package com.learning.pubsub.dto;

public record QueueSizeResponse(
    String subscriberId,
    int queueSize
) {}
