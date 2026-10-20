package com.learning.linkedin.model;

import java.util.ArrayList;
import java.util.List;

public class Profile {
    private final String userId;
    private String headline;
    private String summary;
    private final List<Experience> experiences;
    private final List<String> skills;
    private final List<String> connections;

    public Profile(String userId) {
        this.userId = userId;
        this.experiences = new ArrayList<>();
        this.skills = new ArrayList<>();
        this.connections = new ArrayList<>();
    }

    public synchronized void addConnection(String connectionId) {
        if (!connections.contains(connectionId)) {
            connections.add(connectionId);
        }
    }

    public synchronized void addExperience(Experience experience) {
        experiences.add(experience);
    }

    public synchronized void addSkill(String skill) {
        if (!skills.contains(skill)) {
            skills.add(skill);
        }
    }

    public String getUserId() { return userId; }
    public String getHeadline() { return headline; }
    public String getSummary() { return summary; }
    public List<Experience> getExperiences() { return new ArrayList<>(experiences); }
    public List<String> getSkills() { return new ArrayList<>(skills); }
    public List<String> getConnections() { return new ArrayList<>(connections); }

    public void setHeadline(String headline) { this.headline = headline; }
    public void setSummary(String summary) { this.summary = summary; }
}
