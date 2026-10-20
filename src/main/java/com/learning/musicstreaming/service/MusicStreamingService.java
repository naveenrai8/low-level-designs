package com.learning.musicstreaming.service;

import com.learning.musicstreaming.model.Playlist;
import com.learning.musicstreaming.model.Song;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MusicStreamingService {
    private final ConcurrentHashMap<String, Song> songs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Playlist> playlists = new ConcurrentHashMap<>();

    public void addSong(Song song) {
        songs.put(song.getSongId(), song);
    }

    public Playlist createPlaylist(String userId, String name) {
        Playlist playlist = new Playlist(userId, name);
        playlists.put(playlist.getPlaylistId(), playlist);
        return playlist;
    }

    public void addSongToPlaylist(String playlistId, String songId) throws Exception {
        Playlist playlist = playlists.get(playlistId);
        if (playlist == null) throw new Exception("Playlist not found");
        if (!songs.containsKey(songId)) throw new Exception("Song not found");

        playlist.addSong(songId);
    }

    public List<Song> searchSongs(String keyword) {
        return songs.values().stream()
                .filter(song -> song.getTitle().toLowerCase().contains(keyword.toLowerCase()) ||
                               song.getArtist().toLowerCase().contains(keyword.toLowerCase()))
                .collect(Collectors.toList());
    }

    public Song getSong(String songId) {
        return songs.get(songId);
    }

    public Playlist getPlaylist(String playlistId) {
        return playlists.get(playlistId);
    }

    public List<Song> getAllSongs() {
        return List.copyOf(songs.values());
    }
}
