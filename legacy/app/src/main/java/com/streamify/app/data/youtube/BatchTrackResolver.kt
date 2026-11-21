package com.streamify.app.data.youtube
import android.content.Context
import com.streamify.app.data.discovery.FuzzyTitleMatcher
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeMusicSearchApi
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import com.streamify.app.util.SLog

data class ImportProgress(
    val total: Int,
    val completed: Int,
    val currentTrackTitle: String,
    val playlistId: String = "",
    val isComplete: Boolean = false,
    val errorMessage: String? = null
)

object BatchTrackResolver {

    fun resolveAndImportPlaylist(
        playlist: ScrapedPlaylist,
        context: Context
    ): Flow<ImportProgress> = channelFlow {
        val totalTracks = playlist.tracks.size
        if (totalTracks == 0) {
            send(ImportProgress(0, 0, "", isComplete = false, errorMessage = "No tracks found in playlist link."))
            return@channelFlow
        }

        send(ImportProgress(total = totalTracks, completed = 0, currentTrackTitle = "Initializing fast importer..."))

        // 1. Create Playlist Once
        val newPlaylist = PlaylistRepository.createPlaylist(
            name = playlist.name,
            description = "Imported playlist (${playlist.tracks.size} tracks)"
        )

        // 2. Pre-Load Local Library in Memory for O(1) Lookups (Eliminates 100 SQLite scans)
        val allLocal = TrackRepository.getAllTracks()
        val localTrackMap = HashMap<Long, Track>()
        for (t in allLocal) {
            val hash = FuzzyTitleMatcher.extractRootHash(t.title)
            if (hash != 0L) {
                localTrackMap[hash] = t
            }
        }

        val completedCount = AtomicInteger(0)
        // Array of fixed size to preserve EXACT original playlist order
        val resolvedTracks = arrayOfNulls<Int>(totalTracks)

        // Bounded concurrency semaphore for external search fallback (Spotify/Apple Music)
        val searchSemaphore = Semaphore(3)

        coroutineScope {
            val deferredList = playlist.tracks.mapIndexed { index, scraped ->
                async(Dispatchers.IO) {
                    try {
                        var resolvedId: Int? = null

                        // FAST-PATH 1: YouTube / YTM direct videoId (0-search, instant <1ms)
                        if (scraped.videoId.isNotBlank()) {
                            val canonicalUrl = "https://www.youtube.com/watch?v=${scraped.videoId}"
                            val trackModel = Track(
                                id = 0,
                                title = scraped.title,
                                artist = scraped.artist.ifBlank { "YouTube Music" },
                                album = playlist.name,
                                durationSec = scraped.durationSec,
                                filepath = canonicalUrl,
                                coverArtPath = scraped.thumbnailUrl,
                                bpm = 0f,
                                key = "",
                                lyricsPath = null,
                                source = "online_stream"
                            )
                            val savedTrack = TrackRepository.registerStreamedTrack(trackModel, context)
                            resolvedId = savedTrack.id
                        } else {
                            // FAST-PATH 2: Check in-memory local library first
                            val rootHash = FuzzyTitleMatcher.extractRootHash(scraped.title)
                            val localMatch = if (rootHash != 0L) localTrackMap[rootHash] else null

                            if (localMatch != null && localMatch.id > 0) {
                                resolvedId = localMatch.id
                            } else {
                                // SLOW-PATH 3: Throttled search fallback (for Spotify / Apple Music links)
                                searchSemaphore.withPermit {
                                    delay(100) // Stagger requests to eliminate Innertube rate-limits
                                    val query = "${scraped.title} ${scraped.artist}".trim()
                                    val searchResults = YouTubeMusicSearchApi.search(query, maxResults = 1)
                                    val bestResult = searchResults.firstOrNull()

                                    if (bestResult != null) {
                                        val videoId = YouTubeStreamResolver.extractVideoId(bestResult.url) ?: ""
                                        val canonicalUrl = if (videoId.isNotBlank()) {
                                            YouTubeStreamResolver.getCanonicalWatchUrl(videoId)
                                        } else {
                                            bestResult.url
                                        }

                                        val trackModel = Track(
                                            id = 0,
                                            title = scraped.title,
                                            artist = scraped.artist.ifBlank { bestResult.uploader },
                                            album = playlist.name,
                                            durationSec = bestResult.duration,
                                            filepath = canonicalUrl ?: bestResult.url,
                                            coverArtPath = bestResult.thumbnail.takeIf { it.isNotBlank() },
                                            bpm = 0f,
                                            key = "",
                                            lyricsPath = null,
                                            source = "online_stream"
                                        )
                                        val savedTrack = TrackRepository.registerStreamedTrack(trackModel, context)
                                        resolvedId = savedTrack.id
                                    }
                                }
                            }
                        }

                        val id = resolvedId
                        if (id != null && id > 0) {
                            resolvedTracks[index] = id
                        }
                    } catch (e: Exception) {
                        SLog.st("BatchTrackResolver", "BatchTrackResolver.resolveAndImportPlaylist failed", e)
                    } finally {
                        val done = completedCount.incrementAndGet()
                        send(
                            ImportProgress(
                                total = totalTracks,
                                completed = done,
                                currentTrackTitle = "${scraped.title} - ${scraped.artist}",
                                playlistId = newPlaylist.id
                            )
                        )
                    }
                }
            }

            deferredList.awaitAll()
        }

        // 4. Single Disk/DB Write: Overwrite Playlist Tracks Once at the End in EXACT Order
        val orderedTrackIds = resolvedTracks.filterNotNull()
        PlaylistRepository.overwritePlaylistTracks(newPlaylist.id, orderedTrackIds)

        // 5. Complete
        send(
            ImportProgress(
                total = totalTracks,
                completed = totalTracks,
                currentTrackTitle = "Import Complete!",
                playlistId = newPlaylist.id,
                isComplete = true
            )
        )
    }.flowOn(Dispatchers.IO)
}
