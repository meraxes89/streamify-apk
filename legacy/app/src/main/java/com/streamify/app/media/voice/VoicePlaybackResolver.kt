package com.streamify.app.media.voice

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.streamify.app.data.models.Track
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/**
 * VoicePlaybackResolver (Gap #56) — maps resolved [VoiceCommand]s onto
 * playable MediaItems.
 *
 * Resolution discipline:
 *  • Local/downloaded tracks resolve instantly (file URIs, zero network).
 *  • Remote tracks resolve at most [MAX_REMOTE_RESOLUTIONS] fresh CDN URLs
 *    per voice action — a spoken "shuffle my likes" must never stall for
 *    30 sequential network round-trips; later items continue through the
 *    service's existing JIT token-renewal auto-advance pipeline.
 *  • Every returned item keeps a stable mediaId so error recovery can
 *    re-resolve it after CDN token expiry.
 */
object VoicePlaybackResolver {

    /** Remote URL resolutions budgeted per voice action. */
    const val MAX_REMOTE_RESOLUTIONS: Int = 5

    suspend fun resolve(command: VoiceCommand): List<MediaItem> = withContext(Dispatchers.IO) {
        runCatching {
            when (command) {
                VoiceCommand.PlayDaylist -> daylistItems()
                VoiceCommand.PlayLikedShuffle -> likedItems(shuffle = true)
                is VoiceCommand.PlayPlaylist -> playlistItems(command.nameQuery)
                is VoiceCommand.PlaySearch -> searchItems(command.query, command.shuffle)
                is VoiceCommand.JoinJam -> {
                    // Recognition only: joining a room is a navigation-side
                    // effect, not a media-item action. The Jam voice flow
                    // consumes this branch when it lands.
                    SLog.i("VoiceHandler", "join-jam voice intent recognized (room=${command.roomCode})")
                    emptyList()
                }
            }
        }.getOrElse { t ->
            SLog.st("VoiceHandler", "voice resolution failed", t)
            emptyList()
        }
    }

    private suspend fun daylistItems(): List<MediaItem> {
        val now = LocalDateTime.now()
        val topArtists = com.streamify.app.data.repository.TrackRepository.getTopPlayedTracks(20)
            .map { it.artist }
            .filter { it.isNotBlank() && it != "Unknown Artist" }
            .map { it.trim().lowercase() }
            .distinct()
            .take(3)
        val daylist = com.streamify.app.data.discovery.DaylistScheduler.currentDaylist(
            hour = now.hour,
            dayOfWeek = now.dayOfWeek,
            topArtists = topArtists
        )
        return daylist.tracks.map { t ->
            Track(
                id = t.videoId.hashCode(),
                title = t.title,
                artist = t.artist,
                coverArtPath = t.thumbnailUrl.takeIf { it.isNotBlank() },
                ytmVideoId = t.videoId
            )
        }.let { pack(it) }
    }

    private suspend fun likedItems(shuffle: Boolean): List<MediaItem> {
        val liked = com.streamify.app.data.repository.TrackRepository.getLikedTracks()
        val ordered = if (shuffle) liked.shuffled() else liked
        return pack(ordered)
    }

    private suspend fun playlistItems(nameQuery: String): List<MediaItem> {
        val playlists = com.streamify.app.data.repository.PlaylistRepository.getPlaylists()
        val needle = nameQuery.trim().lowercase()
        val playlist = playlists.firstOrNull { it.name.lowercase().contains(needle) }
            ?: playlists.firstOrNull { needle.contains(it.name.lowercase()) }
            ?: return emptyList()
        val all = com.streamify.app.data.repository.TrackRepository.getAllTracks()
        val byId = all.associateBy { it.id }
        val ordered = playlist.trackIds
            .sortedBy { id -> playlist.trackPositions[id] ?: Double.MAX_VALUE }
            .mapNotNull { byId[it] }
        return pack(ordered)
    }

    private suspend fun searchItems(query: String, shuffle: Boolean): List<MediaItem> {
        val hits = com.streamify.app.data.repository.TrackRepository.searchTracks(query)
        val ordered = if (shuffle) hits.shuffled() else hits
        return pack(ordered)
    }

    /**
     * Attach playback URIs under the resolution budget: local files are
     * free, remote videoIds resolve up to [MAX_REMOTE_RESOLUTIONS].
     */
    private suspend fun pack(tracks: List<Track>): List<MediaItem> {
        var remoteBudget = MAX_REMOTE_RESOLUTIONS
        val items = mutableListOf<MediaItem>()
        for (track in tracks) {
            if (remoteBudget <= 0 && track.ytmVideoId != null && !track.filepath.startsWith("/")) continue

            val uri: Uri? = when {
                track.filepath.startsWith("/") -> Uri.fromFile(File(track.filepath))
                track.filepath.startsWith("http") -> Uri.parse(track.filepath)
                track.ytmVideoId != null && remoteBudget > 0 -> {
                    val resolved = com.streamify.app.data.network.YouTubeStreamResolver
                        .resolveStreamUrl(track.ytmVideoId)
                    remoteBudget--
                    resolved?.streamUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                }
                else -> null
            }
            if (uri == null) continue

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .apply { track.coverArtPath?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            items.add(
                MediaItem.Builder()
                    .setMediaId(track.ytmVideoId ?: track.filepath.ifBlank { track.id.toString() })
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
            )
        }
        return items
    }

    /**
     * Plain mediaId resolution (ACTION_PLAY_FROM_MEDIA_ID path shared by
     * Assistant and Android Auto taps): mediaId is a videoId or a path.
     */
    suspend fun resolveMediaIdItem(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
        runCatching {
            val id = item.mediaId
            if (id.startsWith("/") || id.startsWith("http")) {
                val uri = if (id.startsWith("/")) Uri.fromFile(File(id)) else Uri.parse(id)
                item.buildUpon().setUri(uri).build()
            } else {
                val resolved = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamUrl(id)
                if (resolved == null || resolved.streamUrl.isBlank()) null
                else item.buildUpon().setUri(Uri.parse(resolved.streamUrl)).build()
            }
        }.getOrNull()
    }
}
