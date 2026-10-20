package com.learning.socialnetwork.model;

import java.util.ArrayList;
import java.util.List;

public class User {
    private final String userId;
    private final String name;
    private final List<String> friends;

    public User(String userId, String name) {
        this.userId = userId;
        this.name = name;
        this.friends = new ArrayList<>();
    }

    public synchronized void addFriend(String friendId) {
        if (!friends.contains(friendId)) {
            friends.add(friendId);
        }
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public List<String> getFriends() { return new ArrayList<>(friends); }
}
