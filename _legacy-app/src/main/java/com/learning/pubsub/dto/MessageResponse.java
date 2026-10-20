package com.learning.pubsub.dto;

import java.time.Instant;

public record MessageResponse(
    String messageId,
    String topic,
    String content,
    Instant timestamp
) {}
