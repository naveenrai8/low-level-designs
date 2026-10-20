package com.learning.pubsub.controller;

import com.learning.pubsub.dto.*;

import com.learning.pubsub.model.Message;
import com.learning.pubsub.service.MessageBrokerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pubsub")
@Tag(name = "Pub-Sub", description = "Publish-Subscribe messaging operations")
public class PubSubController {

    private final MessageBrokerService messageBrokerService;

    public PubSubController(MessageBrokerService messageBrokerService) {
        this.messageBrokerService = messageBrokerService;
    }

    @PostMapping("/subscribe")
    @Operation(summary = "Subscribe to a topic")
    public ResponseEntity<Map<String, String>> subscribe(@Valid @RequestBody SubscribeRequest request) {
        String subscriberId = messageBrokerService.subscribe(request.topic());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("subscriberId", subscriberId, "topic", request.topic()));
    }

    @DeleteMapping("/subscribe/{subscriberId}")
    @Operation(summary = "Unsubscribe from topic")
    public ResponseEntity<Void> unsubscribe(@PathVariable String subscriberId) {
        boolean success = messageBrokerService.unsubscribe(subscriberId);
        return success ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/publish")
    @Operation(summary = "Publish a message to a topic")
    public ResponseEntity<Map<String, String>> publish(@Valid @RequestBody PublishRequest request) {
        messageBrokerService.publish(request.topic(), request.content());
        return ResponseEntity.ok(Map.of("status", "published", "topic", request.topic()));
    }

    @GetMapping("/messages/{subscriberId}")
    @Operation(summary = "Poll for next message")
    public ResponseEntity<MessageResponse> pollMessage(@PathVariable String subscriberId) {
        Message message = messageBrokerService.pollMessage(subscriberId);
        if (message == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new MessageResponse(
            message.getMessageId(),
            message.getTopic(),
            message.getContent(),
            message.getTimestamp()
        ));
    }

    @GetMapping("/topics/{topic}/subscribers/count")
    @Operation(summary = "Get subscriber count for topic")
    public ResponseEntity<SubscriberCountResponse> getSubscriberCount(@PathVariable String topic) {
        int count = messageBrokerService.getSubscriberCount(topic);
        return ResponseEntity.ok(new SubscriberCountResponse(topic, count));
    }

    @GetMapping("/subscribers/{subscriberId}/queue/size")
    @Operation(summary = "Get message queue size for subscriber")
    public ResponseEntity<QueueSizeResponse> getQueueSize(@PathVariable String subscriberId) {
        int size = messageBrokerService.getQueueSize(subscriberId);
        return ResponseEntity.ok(new QueueSizeResponse(subscriberId, size));
    }
}
