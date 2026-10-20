package com.learning.pubsub.dto;

import jakarta.validation.constraints.NotBlank;

public record SubscribeRequest(
    @NotBlank(message = "Topic cannot be blank")
    String topic
) {}
