package com.learning.linkedin.model;

public class Experience {
    private final String company;
    private final String title;
    private final String duration;

    public Experience(String company, String title, String duration) {
        this.company = company;
        this.title = title;
        this.duration = duration;
    }

    public String getCompany() { return company; }
    public String getTitle() { return title; }
    public String getDuration() { return duration; }
}
