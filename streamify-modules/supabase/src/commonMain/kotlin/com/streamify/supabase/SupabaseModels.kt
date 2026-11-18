package com.streamify.supabase

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String = "",
    val bio: String = "Music lover on Streamify 🎧",
    val isAdmin: Boolean = false,
    val totalPlays: Int = 0,
    val listeningSeconds: Long = 0L,
    val favoriteGenre: String = "All",
    val topTrack: String = "",
    val isPrivate: Boolean = false,
    val createdAt: String = "",
    val lastActiveAt: String = ""
)

@Serializable
data class PlaybackTelemetryEvent(
    val userId: String,
    val videoId: String,
    val trackTitle: String,
    val artistName: String,
    val durationSeconds: Long,
    val listenedSeconds: Long,
    val timestamp: Long
)

@Serializable
data class WrappedCloudSnapshot(
    val userId: String,
    val year: Int,
    val totalMinutes: Long,
    val topSongsJson: String,
    val topArtistsJson: String,
    val topAlbumsJson: String,
    val biggestDay: String = "",
    val dominantGenre: String = "",
    val personaHeadline: String = "",
    val personaSummary: String = "",
    val updatedAt: Long
)

@Serializable
data class AdminTelemetry(
    val totalUsers: Int,
    val totalTracks: Int,
    val totalPlaylists: Int,
    val activeJamSessions: Int,
    val totalComments: Int,
    val totalLikes: Int,
    val totalPlays: Long,
    val dau24h: Int,
    val serverStatus: String,
    val rpcHealthy: Boolean,
    val engineMode: String,
    val latencyMs: Long
)
