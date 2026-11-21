package com.maxrave.simpmusic.data.scraper

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class PlaylistImportProgress(
    val stage: ImportStage,
    val totalTracks: Int = 0,
    val resolvedTracks: Int = 0,
    val currentTrackTitle: String = "",
    val playlistId: Long? = null,
    val errorMessage: String? = null,
)

enum class ImportStage {
    SCRAPING,
    RESOLVING,
    SAVING,
    COMPLETED,
    ERROR,
}

class PlaylistImportManager(
    private val externalScraper: ExternalPlaylistScraper = ExternalPlaylistScraper(),
    private val playlistRepository: PlaylistRepository,
    private val searchRepository: SearchRepository,
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
) {

    fun importPlaylistFromUrl(rawUrl: String): Flow<PlaylistImportProgress> = channelFlow {
        val url = rawUrl.trim()
        send(PlaylistImportProgress(stage = ImportStage.SCRAPING))

        try {
            if (url.contains("youtube.com") || url.contains("youtu.be")) {
                importYouTubePlaylist(url, this)
            } else {
                importExternalPlaylist(url, this)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Import failed: ${e.message}")
            send(PlaylistImportProgress(stage = ImportStage.ERROR, errorMessage = e.message ?: "Failed to import playlist"))
        }
    }

    private suspend fun importYouTubePlaylist(
        url: String,
        channel: kotlinx.coroutines.channels.ProducerScope<PlaylistImportProgress>,
    ) {
        val playlistId = when {
            url.contains("list=") -> url.substringAfter("list=").substringBefore("&").substringBefore("#")
            else -> ""
        }
        if (playlistId.isBlank()) {
            channel.send(PlaylistImportProgress(stage = ImportStage.ERROR, errorMessage = "Invalid YouTube playlist URL"))
            return
        }

        channel.send(PlaylistImportProgress(stage = ImportStage.SCRAPING))
        val playlistResource = playlistRepository.getFullPlaylistData(playlistId, "").firstOrNull()
        if (playlistResource !is Resource.Success || playlistResource.data == null) {
            channel.send(
                PlaylistImportProgress(
                    stage = ImportStage.ERROR,
                    errorMessage = playlistResource?.message ?: "Could not load YouTube playlist",
                ),
            )
            return
        }

        val playlistData = playlistResource.data!!
        val tracks = playlistData.tracks
        channel.send(
            PlaylistImportProgress(
                stage = ImportStage.SAVING,
                totalTracks = tracks.size,
                resolvedTracks = tracks.size,
            ),
        )

        // Pre-insert tracks into song database
        val videoIds = mutableListOf<String>()
        for (track in tracks) {
            videoIds.add(track.videoId)
            val songEntity = track.toSongEntity()
            songRepository.insertSong(songEntity).lastOrNull()
        }

        val localPlaylist = LocalPlaylistEntity(
            title = playlistData.title.ifBlank { "Imported YouTube Playlist" },
            tracks = videoIds,
            thumbnail = playlistData.thumbnails.lastOrNull()?.url,
        )

        val newId = localPlaylistRepository.insertLocalPlaylist(localPlaylist, "Imported Playlist").lastOrNull()
        val createdId = (newId?.data as? Long) ?: (newId?.message?.toLongOrNull())

        channel.send(
            PlaylistImportProgress(
                stage = ImportStage.COMPLETED,
                totalTracks = tracks.size,
                resolvedTracks = tracks.size,
                playlistId = createdId,
            ),
        )
    }

    private suspend fun importExternalPlaylist(
        url: String,
        channel: kotlinx.coroutines.channels.ProducerScope<PlaylistImportProgress>,
    ) = withContext(Dispatchers.IO) {
        val scraped = externalScraper.scrape(url)
        val total = scraped.tracks.size
        if (total == 0) {
            channel.send(PlaylistImportProgress(stage = ImportStage.ERROR, errorMessage = "No tracks found in the playlist"))
            return@withContext
        }

        channel.send(PlaylistImportProgress(stage = ImportStage.RESOLVING, totalTracks = total, resolvedTracks = 0))

        val searchSemaphore = Semaphore(3)
        val resolvedVideoIds = arrayOfNulls<String>(total)
        var resolvedCount = 0

        coroutineScope {
            scraped.tracks.mapIndexed { index, track ->
                async {
                    val query = "${track.title} ${track.artist}".trim()
                    try {
                        searchSemaphore.withPermit {
                            delay(100)
                            channel.send(
                                PlaylistImportProgress(
                                    stage = ImportStage.RESOLVING,
                                    totalTracks = total,
                                    resolvedTracks = resolvedCount,
                                    currentTrackTitle = "${track.title} - ${track.artist}",
                                ),
                            )

                            val searchFlow = searchRepository.getSearchDataSong(query)
                            val searchResult = searchFlow.firstOrNull()
                            val topSong = (searchResult as? Resource.Success)?.data?.firstOrNull()

                            if (topSong != null) {
                                resolvedVideoIds[index] = topSong.videoId
                                val songEntity = topSong.toTrack().toSongEntity()
                                songRepository.insertSong(songEntity).lastOrNull()
                            }
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed resolving track $query: ${e.message}")
                    } finally {
                        resolvedCount++
                    }
                }
            }.awaitAll()
        }

        val nonNullVideoIds = resolvedVideoIds.filterNotNull()
        channel.send(
            PlaylistImportProgress(
                stage = ImportStage.SAVING,
                totalTracks = total,
                resolvedTracks = nonNullVideoIds.size,
            ),
        )

        val localPlaylist = LocalPlaylistEntity(
            title = scraped.name,
            tracks = nonNullVideoIds,
        )

        val newId = localPlaylistRepository.insertLocalPlaylist(localPlaylist, "Imported Playlist").lastOrNull()
        val createdId = (newId?.data as? Long) ?: (newId?.message?.toLongOrNull())

        channel.send(
            PlaylistImportProgress(
                stage = ImportStage.COMPLETED,
                totalTracks = total,
                resolvedTracks = nonNullVideoIds.size,
                playlistId = createdId,
            ),
        )
    }

    companion object {
        private const val TAG = "PlaylistImportManager"
    }
}
