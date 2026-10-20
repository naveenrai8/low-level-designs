package com.learning.stackoverflow.controller;

import com.learning.stackoverflow.model.Question;
import com.learning.stackoverflow.service.QuestionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/questions")
@Tag(name = "Stack Overflow - Questions")
public class QuestionController {
    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping
    public ResponseEntity<Question> postQuestion(
            @RequestParam Long authorId,
            @RequestParam String title,
            @RequestParam String body,
            @RequestParam Set<String> tags) {
        Question question = questionService.postQuestion(authorId, title, body, tags);
        return ResponseEntity.ok(question);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Question> getQuestion(@PathVariable Long id) {
        return questionService.getQuestion(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Question>> search(
            @RequestParam(required = false) String keywords,
            @RequestParam(required = false) String tag) {
        List<Question> results = keywords != null
                ? questionService.searchByKeywords(keywords)
                : questionService.searchByTag(tag);
        return ResponseEntity.ok(results);
    }
}
