package com.learning.stackoverflow.service;

import com.learning.stackoverflow.model.User;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UserService {
    private final ConcurrentHashMap<Long, User> users = new ConcurrentHashMap<>();
    private final AtomicLong userIdCounter = new AtomicLong(0);

    public User createUser(String username, String email) {
        Long id = userIdCounter.incrementAndGet();
        User user = new User(id, username, email);
        users.put(id, user);
        return user;
    }

    public Optional<User> getUser(Long id) {
        return Optional.ofNullable(users.get(id));
    }

    public void updateReputation(Long userId, int points) {
        getUser(userId).ifPresent(user -> user.addReputation(points));
    }
}
