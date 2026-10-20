package com.learning.pubsub.dto;

public record SubscriberCountResponse(
    String topic,
    int subscriberCount
) {}
