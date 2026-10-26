package com.streamify.app.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.streamify.app.data.models.Track
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.jam.JamEngine
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * WearSessionBridge — single-responsibility composable wiring the live
 * player into the WearOS session (sprint P2, god-object decomposition).
 *
 * Owns exactly two wirings, extracted verbatim from MainActivity:
 *   1. [WearSessionManager.inputHandler]: wrist transport (play/pause/skip),
 *      volume steps, Jam upvotes, queue adds and the quick-playlist launch
 *   2. the compact Now Playing mirror loop (5 s cadence, IO transport; Jam
 *      rows carry live vote counts)
 *
 * Behavior identical: same effect keys ([LaunchedEffect] on the view model),
 * same 5 s cadence, same skip-when-no-track semantics.
 */
@Composable
fun WearSessionBridgeEffect(playerViewModel: PlayerViewModel) {
    LaunchedEffect(playerViewModel) {
        WearSessionManager.inputHandler =
            WearInputHandler(
                object : WearActionSink {
                    override fun playPause() { playerViewModel.togglePlayPause() }
                    override fun skipNext() { playerViewModel.skipNext() }
                    override fun skipPrevious() { playerViewModel.skipPrevious() }

                    override fun volumeStep(delta: Float): Boolean {
                        val ctrl = playerViewModel.getController() ?: return false
                        return runCatching {
                            ctrl.setVolume((ctrl.volume + delta).coerceIn(0f, 1f))
                            true
                        }.getOrDefault(false)
                    }

                    override fun jamUpvote(trackId: Int): Boolean {
                        val track = JamEngine.queue.value
                            .find { it.id == trackId } ?: return false
                        return runCatching {
                            JamEngine.castUpvote(track)
                        }.getOrDefault(false)
                    }

                    override fun queueAdd(videoId: String): Boolean {
                        if (!JamEngine.isActive()) return false
                        val stub = Track(
                            id = videoId.hashCode(),
                            title = videoId,
                            artist = "",
                            ytmVideoId = videoId
                        )
                        return runCatching {
                            JamEngine.addToQueue(stub, "Watch")
                        }.getOrDefault(false)
                    }

                    override fun launchQuickPlaylist(): Boolean {
                        return runCatching {
                            val liked = TrackRepository.likedTracks.value
                            if (liked.isEmpty()) return false
                            playerViewModel.playCollection(liked.shuffled())
                            true
                        }.getOrDefault(false)
                    }
                }
            )

        // Compact Now Playing mirror to the wrist (5s cadence,
        // IO transport; Jam rows carry live vote counts).
        while (true) {
            delay(5000L)
            val state = playerViewModel.playerState.value
            val track = state.currentTrack ?: continue
            val jamActive = JamEngine.isActive()
            val queueRows = if (jamActive) {
                JamEngine.queue.value.take(3).map { t ->
                    WearQueueEntry(
                        trackId = t.id,
                        title = t.title,
                        artist = t.artist,
                        votes = JamEngine.voteCountFor(t)
                    )
                }
            } else {
                emptyList()
            }
            WearSessionManager.publishNowPlaying(
                WearNowPlayingState(
                    trackTitle = track.title,
                    artist = track.artist,
                    artworkUrl = track.coverArtPath,
                    isPlaying = state.isPlaying,
                    positionMs = playerViewModel.positionMs.value,
                    durationMs = state.duration,
                    jamActive = jamActive,
                    jamQueueTop = queueRows,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }
}
