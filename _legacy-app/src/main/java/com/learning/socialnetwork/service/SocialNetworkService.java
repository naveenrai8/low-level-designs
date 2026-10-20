package com.learning.socialnetwork.service;

import com.learning.socialnetwork.model.Post;
import com.learning.socialnetwork.model.User;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SocialNetworkService {
    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Post> posts = new ConcurrentHashMap<>();

    public User createUser(String name) {
        String userId = UUID.randomUUID().toString();
        User user = new User(userId, name);
        users.put(userId, user);
        return user;
    }

    public void addFriend(String userId1, String userId2) throws Exception {
        User user1 = users.get(userId1);
        User user2 = users.get(userId2);

        if (user1 == null || user2 == null) {
            throw new Exception("User not found");
        }

        user1.addFriend(userId2);
        user2.addFriend(userId1);
    }

    public Post createPost(String userId, String content) throws Exception {
        if (!users.containsKey(userId)) {
            throw new Exception("User not found");
        }

        String postId = UUID.randomUUID().toString();
        Post post = new Post(postId, userId, content);
        posts.put(postId, post);
        return post;
    }

    public void likePost(String postId) throws Exception {
        Post post = posts.get(postId);
        if (post == null) {
            throw new Exception("Post not found");
        }
        post.like();
    }

    public List<Post> getUserPosts(String userId) {
        return posts.values().stream()
                .filter(post -> post.getUserId().equals(userId))
                .collect(Collectors.toList());
    }

    public User getUser(String userId) {
        return users.get(userId);
    }

    public Post getPost(String postId) {
        return posts.get(postId);
    }

    public List<Post> getAllPosts() {
        return List.copyOf(posts.values());
    }
}
