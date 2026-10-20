package com.learning.musicstreaming.controller;

import com.learning.musicstreaming.dto.PlaylistRequest;
import com.learning.musicstreaming.dto.SongRequest;
import com.learning.musicstreaming.model.Playlist;
import com.learning.musicstreaming.model.Song;
import com.learning.musicstreaming.service.MusicStreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/music")
@Tag(name = "Music Streaming", description = "Music streaming operations")
public class MusicStreamingController {

    private final MusicStreamingService musicStreamingService;

    public MusicStreamingController(MusicStreamingService musicStreamingService) {
        this.musicStreamingService = musicStreamingService;
    }

    @PostMapping("/songs")
    @Operation(summary = "Add a song")
    public ResponseEntity<Song> addSong(@Valid @RequestBody SongRequest request) {
        Song song = new Song(
            UUID.randomUUID().toString(),
            request.title(),
            request.artist(),
            request.album(),
            request.durationSeconds()
        );
        musicStreamingService.addSong(song);
        return ResponseEntity.status(HttpStatus.CREATED).body(song);
    }

    @GetMapping("/songs")
    @Operation(summary = "Get all songs")
    public ResponseEntity<List<Song>> getAllSongs() {
        return ResponseEntity.ok(musicStreamingService.getAllSongs());
    }

    @GetMapping("/songs/search")
    @Operation(summary = "Search songs")
    public ResponseEntity<List<Song>> searchSongs(@RequestParam String keyword) {
        return ResponseEntity.ok(musicStreamingService.searchSongs(keyword));
    }

    @GetMapping("/songs/{songId}")
    @Operation(summary = "Get song details")
    public ResponseEntity<Song> getSong(@PathVariable String songId) {
        Song song = musicStreamingService.getSong(songId);
        return song != null ? ResponseEntity.ok(song) : ResponseEntity.notFound().build();
    }

    @PostMapping("/playlists")
    @Operation(summary = "Create a playlist")
    public ResponseEntity<Playlist> createPlaylist(@Valid @RequestBody PlaylistRequest request) {
        Playlist playlist = musicStreamingService.createPlaylist(request.userId(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(playlist);
    }

    @GetMapping("/playlists/{playlistId}")
    @Operation(summary = "Get playlist details")
    public ResponseEntity<Playlist> getPlaylist(@PathVariable String playlistId) {
        Playlist playlist = musicStreamingService.getPlaylist(playlistId);
        return playlist != null ? ResponseEntity.ok(playlist) : ResponseEntity.notFound().build();
    }

    @PostMapping("/playlists/{playlistId}/songs/{songId}")
    @Operation(summary = "Add song to playlist")
    public ResponseEntity<?> addSongToPlaylist(
            @PathVariable String playlistId,
            @PathVariable String songId) {
        try {
            musicStreamingService.addSongToPlaylist(playlistId, songId);
            return ResponseEntity.ok("Song added to playlist");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
