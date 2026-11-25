package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.common.Resource
import com.maxrave.data.model.lyrics.Lyrics
import com.maxrave.data.model.lyrics.LyricsProvider
import com.maxrave.data.model.lyrics.toLyrics
import com.maxrave.data.model.lyrics.toLyricsEntity
import com.maxrave.data.model.lyrics.toSyncedLyrics
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.Track
import com.maxrave.domain.data.model.toListName
import com.maxrave.domain.data.model.toSongEntity
import com.maxrave.domain.data.model.toTrack
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.data.DataStoreManager
import com.maxrave.simpmusic.data.DataStoreManager.Companion.FALSE
import com.maxrave.simpmusic.data.DataStoreManager.Companion.TRUE
import com.maxrave.simpmusic.data.model.VoteState
import com.maxrave.simpmusic.media.LiveStreamRegistry
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.vote_submitted

/**
 * Lyrics subsystem decomposition for [SharedViewModel].
 * Decouples provider fetching (SimpMusic, LRCLIB, YouTube, BetterLyrics, Spotify),
 * AI-assisted translation, and crowd-sourced vote submission into a dedicated module.
 */
internal fun SharedViewModel.getLyricsFromFormat(
    isVideo: Boolean,
    song: SongEntity,
    duration: Int,
) {
    if (LiveStreamRegistry.isLive(song.videoId)) return
    _nowPlayingScreenData.update { it.copy(lyricsNotFound = false) }
    viewModelScope.launch {
        val videoId = song.videoId
        log("Get Lyrics From Format for $videoId", LogLevel.WARN)
        val artistName = song.artistName
        val artist =
            if (artistName?.firstOrNull() != null &&
                artistName
                    .firstOrNull()
                    ?.contains("Various Artists") == false
            ) {
                artistName.firstOrNull()
            } else {
                mediaPlayerHandler.nowPlaying
                    .first()
                    ?.metadata
                    ?.artist
                    ?: ""
            }
        resetLyricsVoteState()
        val lyricsProvider = dataStoreManager.lyricsProvider.first()
        when (lyricsProvider) {
            DataStoreManager.SIMPMUSIC -> {
                getSimpMusicLyrics(
                    videoId,
                    song,
                    (artist ?: ""),
                    duration,
                )
            }

            DataStoreManager.LRCLIB -> {
                getLrclibLyrics(
                    song,
                    (artist ?: ""),
                    duration,
                )
            }

            DataStoreManager.YOUTUBE -> {
                getYouTubeCaption(
                    videoId,
                    song,
                    (artist ?: ""),
                    duration,
                )
            }

            DataStoreManager.BETTER_LYRICS -> {
                getBetterLyrics(
                    song,
                    (artist ?: "").toString(),
                    duration,
                )
            }
        }
    }
}

internal suspend fun SharedViewModel.getSimpMusicLyrics(
    videoId: String,
    song: SongEntity,
    artist: String?,
    duration: Int,
) {
    lyricsCanvasRepository.getSimpMusicLyrics(videoId).collectLatest {
        Logger.d(tag, "Get SimpMusic Lyrics for $videoId: ${it::class.simpleName}")
        val data = it.data
        if (it is Resource.Success && data != null) {
            Logger.d(tag, "Get SimpMusic Lyrics Success")
            updateLyrics(
                videoId,
                duration,
                data,
                false,
                LyricsProvider.SIMPMUSIC,
            )
            insertLyrics(
                data.toLyricsEntity(videoId),
            )
            getSimpMusicTranslatedLyrics(
                videoId,
                data,
            )
        } else if (dataStoreManager.spotifyLyrics.first() == TRUE) {
            getSpotifyLyrics(
                song.toTrack().copy(durationSeconds = duration),
                "${song.title} $artist",
                duration,
            )
        } else {
            getLrclibLyrics(
                song,
                (artist ?: ""),
                duration,
            )
        }
    }
}

internal suspend fun SharedViewModel.getYouTubeCaption(
    videoId: String,
    song: SongEntity,
    artist: String?,
    duration: Int,
) {
    lyricsCanvasRepository
        .getYouTubeCaption(dataStoreManager.youtubeSubtitleLanguage.first(), videoId)
        .cancellable()
        .collect { response ->
            val data = response.data
            when (response) {
                is Resource.Success if (data != null) -> {
                    val lyrics = data.first
                    val translatedLyrics = data.second
                    insertLyrics(lyrics.toLyricsEntity(videoId))
                    updateLyrics(
                        videoId,
                        duration,
                        lyrics,
                        false,
                        LyricsProvider.YOUTUBE,
                    )
                    if (translatedLyrics != null) {
                        updateLyrics(
                            videoId,
                            duration,
                            translatedLyrics,
                            true,
                            LyricsProvider.YOUTUBE,
                        )
                    } else {
                        getAITranslationLyrics(
                            videoId,
                            lyrics,
                        )
                    }
                }

                else -> {
                    getSimpMusicLyrics(
                        videoId,
                        song,
                        (artist ?: ""),
                        duration,
                    )
                }
            }
        }
}

internal fun SharedViewModel.getLrclibLyrics(
    song: SongEntity,
    artist: String,
    duration: Int,
) {
    viewModelScope.launch {
        lyricsCanvasRepository
            .getLrclibLyricsData(
                artist,
                song.title,
                duration,
            ).collectLatest { res ->
                val data = res.data
                when (res) {
                    is Resource.Success if (data != null) -> {
                        Logger.d(tag, "Get Lyrics Data Success")
                        updateLyrics(
                            song.videoId,
                            duration,
                            res.data,
                            false,
                            LyricsProvider.LRCLIB,
                        )
                        insertLyrics(
                            res.data?.toLyricsEntity(
                                song.videoId,
                            ) ?: return@collectLatest,
                        )
                        getAITranslationLyrics(
                            song.videoId,
                            data,
                        )
                    }

                    else -> {
                        getSavedLyrics(
                            song.toTrack().copy(
                                durationSeconds = duration,
                            ),
                        )
                    }
                }
            }
    }
}

internal fun SharedViewModel.getBetterLyrics(
    song: SongEntity,
    artist: String,
    duration: Int,
) {
    viewModelScope.launch {
        lyricsCanvasRepository
            .getBetterLyrics(
                artist,
                song.title,
                duration,
            ).collectLatest { res ->
                val data = res.data
                when (res) {
                    is Resource.Success if (data != null) -> {
                        Logger.d(tag, "Get BetterLyrics Success")
                        updateLyrics(
                            song.videoId,
                            duration,
                            data,
                            false,
                            LyricsProvider.BETTER_LYRICS,
                        )
                        insertLyrics(
                            data.toLyricsEntity(
                                song.videoId,
                            ),
                        )
                        getAITranslationLyrics(
                            song.videoId,
                            data,
                        )
                    }

                    else -> {
                        log("Get BetterLyrics Error: ${res.message}", LogLevel.WARN)
                        getSimpMusicLyrics(
                            song.videoId,
                            song,
                            artist,
                            duration,
                        )
                    }
                }
            }
    }
}

internal suspend fun SharedViewModel.getSimpMusicTranslatedLyrics(
    videoId: String,
    lyrics: Lyrics,
) {
    val translationLanguage =
        dataStoreManager.translationLanguage.first()
    lyricsCanvasRepository.getSimpMusicTranslatedLyrics(videoId, translationLanguage).collectLatest { response ->
        val data = response.data
        when (response) {
            is Resource.Success if (data != null) -> {
                if (data.syncType == "RICH_SYNCED") {
                    Logger.w(tag, "SimpMusic translated lyrics are RICH_SYNCED, downvoting and falling back to AI")
                    val simpMusicLyricsId = data.simpMusicLyrics?.id
                    if (!simpMusicLyricsId.isNullOrEmpty()) {
                        viewModelScope.launch {
                            lyricsCanvasRepository
                                .voteSimpMusicTranslatedLyrics(simpMusicLyricsId, false)
                                .collectLatest { voteResult ->
                                    when (voteResult) {
                                        is Resource.Error -> Logger.w(tag, "Downvote RICH_SYNCED translated lyrics error: ${voteResult.message}")
                                        is Resource.Success -> Logger.d(tag, "Downvote RICH_SYNCED translated lyrics success")
                                    }
                                }
                        }
                    }
                    getAITranslationLyrics(videoId, lyrics)
                } else {
                    Logger.d(tag, "Get SimpMusic Translated Lyrics Success")
                    updateLyrics(
                        videoId,
                        0,
                        data,
                        true,
                        LyricsProvider.SIMPMUSIC,
                    )
                }
            }

            else -> {
                Logger.d(tag, "Get SimpMusic Translated Lyrics Error: ${response.message}")
                getAITranslationLyrics(
                    videoId,
                    lyrics,
                )
            }
        }
    }
}

internal suspend fun SharedViewModel.getAITranslationLyrics(
    videoId: String,
    lyrics: Lyrics,
) {
    Logger.d(tag, "Get AI Translation Lyrics for $videoId")
    if (dataStoreManager.useAITranslation.first() == TRUE &&
        dataStoreManager.aiApiKey.first().isNotEmpty() &&
        dataStoreManager.enableTranslateLyric.first() == FALSE
    ) {
        val savedTranslatedLyrics =
            lyricsCanvasRepository
                .getSavedTranslatedLyrics(
                    videoId,
                    dataStoreManager.translationLanguage.first(),
                ).firstOrNull()
        if (savedTranslatedLyrics != null) {
            Logger.d(tag, "Get Saved Translated Lyrics")
            updateLyrics(
                videoId,
                0,
                savedTranslatedLyrics.toLyrics(),
                true,
                LyricsProvider.AI,
            )
        } else {
            val lyricsForAi =
                if (lyrics.syncType == "RICH_SYNCED") {
                    lyrics.toSyncedLyrics()
                } else {
                    lyrics
                }
            lyricsCanvasRepository
                .getAITranslationLyrics(
                    lyricsForAi,
                    dataStoreManager.translationLanguage.first(),
                ).cancellable()
                .collectLatest {
                    val data = it.data
                    when (it) {
                        is Resource.Success if (data != null) -> {
                            Logger.d(tag, "Get AI Translate Lyrics Success")
                            lyricsCanvasRepository.insertTranslatedLyrics(
                                TranslatedLyricsEntity(
                                    videoId = videoId,
                                    language = dataStoreManager.translationLanguage.first(),
                                    error = false,
                                    lines = data.lines,
                                    syncType = data.syncType,
                                ),
                            )
                            updateLyrics(
                                videoId,
                                0,
                                data,
                                true,
                                LyricsProvider.AI,
                            )
                        }

                        else -> {
                            Logger.w(tag, "Get AI Translate Lyrics Error: ${it.message}")
                        }
                    }
                }
        }
    }
}

internal fun SharedViewModel.getSpotifyLyrics(
    track: Track,
    query: String,
    duration: Int? = null,
) {
    viewModelScope.launch {
        Logger.d("Check SpotifyLyrics", "SpotifyLyrics $query")
        lyricsCanvasRepository.getSpotifyLyrics(dataStoreManager, query, duration).cancellable().collect { response ->
            Logger.d("Check SpotifyLyrics", response.toString())
            val data = response.data
            when (response) {
                is Resource.Success -> {
                    if (data != null) {
                        insertLyrics(
                            data.toLyricsEntity(
                                track.videoId,
                            ),
                        )
                        updateLyrics(
                            track.videoId,
                            duration ?: 0,
                            data,
                            false,
                            LyricsProvider.SPOTIFY,
                        )
                        getAITranslationLyrics(
                            track.videoId,
                            data,
                        )
                    }
                }

                else -> {
                    getLrclibLyrics(
                        track.toSongEntity(),
                        track.artists.toListName().firstOrNull() ?: "",
                        duration ?: 0,
                    )
                }
            }
        }
    }
}

internal fun SharedViewModel.setLyricsProvider() {
    viewModelScope.launch {
        val songEntity = nowPlayingState.value?.songEntity ?: return@launch
        val isVideo = nowPlayingState.value?.mediaItem?.isVideo() ?: false
        getLyricsFromFormat(isVideo, songEntity, timeline.value.total.toInt() / 1000)
    }
}

/**
 * Vote for SimpMusic original lyrics (upvote or downvote)
 * @param upvote true for upvote, false for downvote
 */
fun SharedViewModel.voteLyrics(upvote: Boolean) {
    val lyricsData = _nowPlayingScreenData.value.lyricsData
    val lyricsProvider = lyricsData?.lyricsProvider
    val simpMusicLyricsId = lyricsData?.lyrics?.simpMusicLyrics?.id ?: return

    if (lyricsProvider != LyricsProvider.SIMPMUSIC || simpMusicLyricsId.isEmpty()) {
        Logger.w(tag, "Cannot vote: not a SimpMusic lyrics or missing ID")
        return
    }

    viewModelScope.launch {
        _lyricsVoteState.update {
            it?.copy(
                state = VoteState.Loading,
            )
        }
        lyricsCanvasRepository
            .voteSimpMusicLyrics(
                lyricsId = simpMusicLyricsId,
                upvote = upvote,
            ).collectLatest { result ->
                when (result) {
                    is Resource.Error -> {
                        Logger.w(tag, "Vote SimpMusic Lyrics Error ${result.message}")
                        _lyricsVoteState.update {
                            it?.copy(
                                state = VoteState.Error(result.message ?: "Unknown error"),
                            )
                        }
                    }

                    is Resource.Success -> {
                        Logger.d(tag, "Vote SimpMusic Lyrics Success")
                        _lyricsVoteState.update {
                            it?.copy(
                                state = VoteState.Success(upvote),
                                vote = it.vote + if (upvote) 1 else -1,
                            )
                        }
                        makeToast(getString(Res.string.vote_submitted))
                    }
                }
            }
    }
}

/**
 * Vote for SimpMusic translated lyrics (upvote or downvote)
 * @param upvote true for upvote, false for downvote
 */
fun SharedViewModel.voteTranslatedLyrics(upvote: Boolean) {
    val translatedLyrics = _nowPlayingScreenData.value.lyricsData?.translatedLyrics
    val lyricsProvider = translatedLyrics?.second
    val simpMusicLyricsId = translatedLyrics?.first?.simpMusicLyrics?.id ?: return

    if (lyricsProvider != LyricsProvider.SIMPMUSIC || simpMusicLyricsId.isEmpty()) {
        Logger.w(tag, "Cannot vote: not a SimpMusic translated lyrics or missing ID")
        return
    }

    viewModelScope.launch {
        _translatedVoteState.update {
            it?.copy(
                state = VoteState.Loading,
            )
        }
        lyricsCanvasRepository
            .voteSimpMusicTranslatedLyrics(
                translatedLyricsId = simpMusicLyricsId,
                upvote = upvote,
            ).collectLatest { result ->
                when (result) {
                    is Resource.Error -> {
                        Logger.w(tag, "Vote SimpMusic Translated Lyrics Error ${result.message}")
                        _translatedVoteState.update {
                            it?.copy(
                                state = VoteState.Error(result.message ?: "Unknown error"),
                            )
                        }
                    }

                    is Resource.Success -> {
                        Logger.d(tag, "Vote SimpMusic Translated Lyrics Success")
                        _translatedVoteState.update {
                            it?.copy(
                                state = VoteState.Success(upvote),
                                vote = it.vote + if (upvote) 1 else -1,
                            )
                        }
                        makeToast(getString(Res.string.vote_submitted))
                    }
                }
            }
    }
}
