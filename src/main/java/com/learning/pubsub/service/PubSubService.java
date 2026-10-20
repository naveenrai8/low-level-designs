package com.learning.pubsub.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

@Service
public class PubSubService<T> {
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Consumer<T>>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String topic, Consumer<T> subscriber) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).add(subscriber);
    }

    public void unsubscribe(String topic, Consumer<T> subscriber) {
        CopyOnWriteArraySet<Consumer<T>> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers != null) {
            topicSubscribers.remove(subscriber);
        }
    }

    public void publish(String topic, T message) {
        CopyOnWriteArraySet<Consumer<T>> topicSubscribers = subscribers.get(topic);
        if (topicSubscribers != null) {
            topicSubscribers.forEach(subscriber -> subscriber.accept(message));
        }
    }

    public int getSubscriberCount(String topic) {
        CopyOnWriteArraySet<Consumer<T>> topicSubscribers = subscribers.get(topic);
        return topicSubscribers != null ? topicSubscribers.size() : 0;
    }
}
