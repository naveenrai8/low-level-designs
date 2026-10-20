package com.learning.cricket.model;

import java.time.LocalDateTime;

public class Match {
    private final String matchId;
    private final String team1;
    private final String team2;
    private final String venue;
    private final LocalDateTime matchTime;
    private volatile MatchStatus status;
    private int team1Score;
    private int team2Score;

    public Match(String matchId, String team1, String team2, String venue, LocalDateTime matchTime) {
        this.matchId = matchId;
        this.team1 = team1;
        this.team2 = team2;
        this.venue = venue;
        this.matchTime = matchTime;
        this.status = MatchStatus.SCHEDULED;
    }

    public synchronized void updateScore(String team, int runs) {
        if (team.equals(team1)) team1Score += runs;
        else if (team.equals(team2)) team2Score += runs;
    }

    public String getMatchId() { return matchId; }
    public MatchStatus getStatus() { return status; }
    public int getTeam1Score() { return team1Score; }
    public int getTeam2Score() { return team2Score; }
}

enum MatchStatus { SCHEDULED, LIVE, COMPLETED, CANCELLED }
