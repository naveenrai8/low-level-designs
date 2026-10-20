package com.learning.cricket.service;

import com.learning.cricket.model.Match;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CricketService {
    private final ConcurrentHashMap<String, Match> matches = new ConcurrentHashMap<>();

    public Match createMatch(String matchId, String team1, String team2, String venue, java.time.LocalDateTime matchTime) {
        Match match = new Match(matchId, team1, team2, venue, matchTime);
        matches.put(matchId, match);
        return match;
    }

    public void updateScore(String matchId, String team, int runs) {
        Match match = matches.get(matchId);
        if (match != null) {
            match.updateScore(team, runs);
        }
    }

    public Match getMatch(String matchId) {
        return matches.get(matchId);
    }

    public List<Match> getAllMatches() {
        return List.copyOf(matches.values());
    }
}
