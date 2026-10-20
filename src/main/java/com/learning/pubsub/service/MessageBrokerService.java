package com.learning.pubsub.service;

import com.learning.pubsub.model.Message;
import com.learning.pubsub.model.Subscriber;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
public class MessageBrokerService {
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Subscriber>> topicSubscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    public String subscribe(String topic) {
        String subscriberId = UUID.randomUUID().toString();
        Subscriber subscriber = new Subscriber(subscriberId, topic);

        subscribers.put(subscriberId, subscriber);
        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).add(subscriber);

        return subscriberId;
    }

    public boolean unsubscribe(String subscriberId) {
        Subscriber subscriber = subscribers.remove(subscriberId);
        if (subscriber == null) {
            return false;
        }

        CopyOnWriteArraySet<Subscriber> subs = topicSubscribers.get(subscriber.getTopic());
        if (subs != null) {
            subs.remove(subscriber);
        }
        return true;
    }

    public void publish(String topic, String content) {
        Message message = new Message(topic, content);
        CopyOnWriteArraySet<Subscriber> subs = topicSubscribers.get(topic);

        if (subs != null) {
            subs.forEach(subscriber -> subscriber.receiveMessage(message));
        }
    }

    public Message pollMessage(String subscriberId) {
        Subscriber subscriber = subscribers.get(subscriberId);
        return subscriber != null ? subscriber.pollMessage() : null;
    }

    public int getSubscriberCount(String topic) {
        CopyOnWriteArraySet<Subscriber> subs = topicSubscribers.get(topic);
        return subs != null ? subs.size() : 0;
    }

    public int getQueueSize(String subscriberId) {
        Subscriber subscriber = subscribers.get(subscriberId);
        return subscriber != null ? subscriber.getQueueSize() : 0;
    }
}
