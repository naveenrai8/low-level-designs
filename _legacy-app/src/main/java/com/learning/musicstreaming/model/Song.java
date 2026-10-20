package com.learning.musicstreaming.model;

public class Song {
    private final String songId;
    private final String title;
    private final String artist;
    private final String album;
    private final int durationSeconds;

    public Song(String songId, String title, String artist, String album, int durationSeconds) {
        this.songId = songId;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationSeconds = durationSeconds;
    }

    public String getSongId() { return songId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
}
