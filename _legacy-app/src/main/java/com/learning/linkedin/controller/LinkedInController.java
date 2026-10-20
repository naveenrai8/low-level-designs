package com.learning.linkedin.controller;

import com.learning.linkedin.dto.ConnectionRequest;
import com.learning.linkedin.dto.PostRequest;
import com.learning.linkedin.dto.ProfileRequest;
import com.learning.linkedin.model.Post;
import com.learning.linkedin.model.Profile;
import com.learning.linkedin.service.LinkedInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/linkedin")
@Tag(name = "LinkedIn", description = "LinkedIn-like platform operations")
public class LinkedInController {

    private final LinkedInService linkedInService;

    public LinkedInController(LinkedInService linkedInService) {
        this.linkedInService = linkedInService;
    }

    @PostMapping("/profiles")
    @Operation(summary = "Create a profile")
    public ResponseEntity<Profile> createProfile(@Valid @RequestBody ProfileRequest request) {
        Profile profile = linkedInService.createProfile(request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(profile);
    }

    @GetMapping("/profiles/{userId}")
    @Operation(summary = "Get profile details")
    public ResponseEntity<Profile> getProfile(@PathVariable String userId) {
        Profile profile = linkedInService.getProfile(userId);
        return profile != null ? ResponseEntity.ok(profile) : ResponseEntity.notFound().build();
    }

    @PostMapping("/connections")
    @Operation(summary = "Connect two users")
    public ResponseEntity<?> connect(@Valid @RequestBody ConnectionRequest request) {
        try {
            linkedInService.connect(request.userId1(), request.userId2());
            return ResponseEntity.ok("Connection established");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/posts")
    @Operation(summary = "Create a post")
    public ResponseEntity<?> createPost(@Valid @RequestBody PostRequest request) {
        try {
            Post post = linkedInService.createPost(request.userId(), request.content());
            return ResponseEntity.status(HttpStatus.CREATED).body(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/posts")
    @Operation(summary = "Get all posts")
    public ResponseEntity<List<Post>> getAllPosts() {
        return ResponseEntity.ok(linkedInService.getAllPosts());
    }

    @GetMapping("/posts/{postId}")
    @Operation(summary = "Get post details")
    public ResponseEntity<Post> getPost(@PathVariable String postId) {
        Post post = linkedInService.getPost(postId);
        return post != null ? ResponseEntity.ok(post) : ResponseEntity.notFound().build();
    }

    @PostMapping("/posts/{postId}/like")
    @Operation(summary = "Like a post")
    public ResponseEntity<?> likePost(@PathVariable String postId) {
        try {
            linkedInService.likePost(postId);
            return ResponseEntity.ok("Post liked");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
