package com.learning.stackoverflow.service;

import com.learning.stackoverflow.model.Question;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class QuestionService {
    private final ConcurrentHashMap<Long, Question> questions = new ConcurrentHashMap<>();
    private final AtomicLong questionIdCounter = new AtomicLong(0);

    public Question postQuestion(Long authorId, String title, String body, Set<String> tags) {
        Long id = questionIdCounter.incrementAndGet();
        Question question = new Question(id, title, body, authorId, tags);
        questions.put(id, question);
        return question;
    }

    public Optional<Question> getQuestion(Long id) {
        return Optional.ofNullable(questions.get(id));
    }

    public List<Question> searchByKeywords(String keywords) {
        String[] terms = keywords.toLowerCase().split("\\s+");
        return questions.values().stream()
                .filter(q -> {
                    String searchText = (q.getTitle() + " " + q.getBody()).toLowerCase();
                    return Arrays.stream(terms).anyMatch(searchText::contains);
                })
                .sorted(Comparator.comparingInt(Question::getVoteCount).reversed())
                .collect(Collectors.toList());
    }

    public List<Question> searchByTag(String tag) {
        return questions.values().stream()
                .filter(q -> q.getTags().contains(tag))
                .sorted(Comparator.comparingInt(Question::getVoteCount).reversed())
                .collect(Collectors.toList());
    }

    public List<Question> getQuestionsByUser(Long userId) {
        return questions.values().stream()
                .filter(q -> q.getAuthorId().equals(userId))
                .collect(Collectors.toList());
    }
}
