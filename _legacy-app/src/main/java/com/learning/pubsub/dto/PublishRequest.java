package com.learning.pubsub.dto;

import jakarta.validation.constraints.NotBlank;

public record PublishRequest(
    @NotBlank(message = "Topic cannot be blank")
    String topic,

    @NotBlank(message = "Content cannot be blank")
    String content
) {}
