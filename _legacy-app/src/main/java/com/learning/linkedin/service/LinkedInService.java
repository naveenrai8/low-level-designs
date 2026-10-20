package com.learning.linkedin.service;

import com.learning.linkedin.model.Post;
import com.learning.linkedin.model.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LinkedInService {
    private final ConcurrentHashMap<String, Profile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Post> posts = new ConcurrentHashMap<>();

    public Profile createProfile(String userId) {
        Profile profile = new Profile(userId);
        profiles.put(userId, profile);
        return profile;
    }

    public void connect(String userId1, String userId2) throws Exception {
        Profile profile1 = profiles.get(userId1);
        Profile profile2 = profiles.get(userId2);

        if (profile1 == null || profile2 == null) {
            throw new Exception("Profile not found");
        }

        profile1.addConnection(userId2);
        profile2.addConnection(userId1);
    }

    public Post createPost(String userId, String content) throws Exception {
        if (!profiles.containsKey(userId)) {
            throw new Exception("Profile not found");
        }

        Post post = new Post(userId, content);
        posts.put(post.getPostId(), post);
        return post;
    }

    public void likePost(String postId) throws Exception {
        Post post = posts.get(postId);
        if (post == null) {
            throw new Exception("Post not found");
        }
        post.like();
    }

    public Profile getProfile(String userId) {
        return profiles.get(userId);
    }

    public Post getPost(String postId) {
        return posts.get(postId);
    }

    public List<Post> getAllPosts() {
        return List.copyOf(posts.values());
    }
}
