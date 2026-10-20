package com.learning.musicstreaming.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Playlist {
    private final String playlistId;
    private final String userId;
    private final String name;
    private final List<String> songIds;

    public Playlist(String userId, String name) {
        this.playlistId = UUID.randomUUID().toString();
        this.userId = userId;
        this.name = name;
        this.songIds = new ArrayList<>();
    }

    public synchronized void addSong(String songId) {
        if (!songIds.contains(songId)) {
            songIds.add(songId);
        }
    }

    public synchronized void removeSong(String songId) {
        songIds.remove(songId);
    }

    public String getPlaylistId() { return playlistId; }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public List<String> getSongIds() { return new ArrayList<>(songIds); }
}
