package com.learning.socialnetwork.controller;

import com.learning.socialnetwork.dto.FriendRequest;
import com.learning.socialnetwork.dto.PostRequest;
import com.learning.socialnetwork.dto.UserRequest;
import com.learning.socialnetwork.model.Post;
import com.learning.socialnetwork.model.User;
import com.learning.socialnetwork.service.SocialNetworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/socialnetwork")
@Tag(name = "Social Network", description = "Social network operations")
public class SocialNetworkController {

    private final SocialNetworkService socialNetworkService;

    public SocialNetworkController(SocialNetworkService socialNetworkService) {
        this.socialNetworkService = socialNetworkService;
    }

    @PostMapping("/users")
    @Operation(summary = "Create a user")
    public ResponseEntity<User> createUser(@Valid @RequestBody UserRequest request) {
        User user = socialNetworkService.createUser(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Get user details")
    public ResponseEntity<User> getUser(@PathVariable String userId) {
        User user = socialNetworkService.getUser(userId);
        return user != null ? ResponseEntity.ok(user) : ResponseEntity.notFound().build();
    }

    @PostMapping("/friends")
    @Operation(summary = "Add friend")
    public ResponseEntity<?> addFriend(@Valid @RequestBody FriendRequest request) {
        try {
            socialNetworkService.addFriend(request.userId1(), request.userId2());
            return ResponseEntity.ok("Friend added");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/posts")
    @Operation(summary = "Create a post")
    public ResponseEntity<?> createPost(@Valid @RequestBody PostRequest request) {
        try {
            Post post = socialNetworkService.createPost(request.userId(), request.content());
            return ResponseEntity.status(HttpStatus.CREATED).body(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/posts")
    @Operation(summary = "Get all posts")
    public ResponseEntity<List<Post>> getAllPosts() {
        return ResponseEntity.ok(socialNetworkService.getAllPosts());
    }

    @GetMapping("/posts/{postId}")
    @Operation(summary = "Get post details")
    public ResponseEntity<Post> getPost(@PathVariable String postId) {
        Post post = socialNetworkService.getPost(postId);
        return post != null ? ResponseEntity.ok(post) : ResponseEntity.notFound().build();
    }

    @GetMapping("/users/{userId}/posts")
    @Operation(summary = "Get user posts")
    public ResponseEntity<List<Post>> getUserPosts(@PathVariable String userId) {
        return ResponseEntity.ok(socialNetworkService.getUserPosts(userId));
    }

    @PostMapping("/posts/{postId}/like")
    @Operation(summary = "Like a post")
    public ResponseEntity<?> likePost(@PathVariable String postId) {
        try {
            socialNetworkService.likePost(postId);
            return ResponseEntity.ok("Post liked");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
