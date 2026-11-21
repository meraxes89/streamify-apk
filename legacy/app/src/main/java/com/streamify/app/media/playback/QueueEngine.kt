package com.streamify.app.media.playback
import com.streamify.app.radio.ContinuumRadioEngine
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.CanonicalSeedResolver
import com.streamify.app.radio.OnlineRadioEngine
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deterministic queue context representing the user's intent.
 */
enum class QueueContext {
    SINGLE_TAP,    // User tapped one song anywhere → instant playback + background RDAMVM radio
    COLLECTION,    // User plays an album/playlist → ordered queue until exhausted
    JAM            // Shared collaborative session → host-driven synchronization
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QUEUE ENGINE — Pure Spotify / YouTube Music Single-Tap & Radio Pipeline.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object QueueEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var radioJob: Job? = null

    @Volatile
    private var currentContext: QueueContext = QueueContext.SINGLE_TAP
    val context: QueueContext
        get() = currentContext

    // Radio continuation state (chained across /next token pages)
    @Volatile private var radioVideoId: String? = null
    @Volatile private var radioContinuationToken: String? = null
    @Volatile private var radioPagesFetched: Int = 0

    fun resetRadioState() {
        radioJob?.cancel()
        radioVideoId = null
        radioContinuationToken = null
        radioPagesFetched = 0
    }

    /**
     * User taps ONE song anywhere (Home, Search, Library, Artist).
     * 1. Plays the tapped track immediately (0ms delay).
     * 2. Replaces upcoming queue with YouTube Music's RDAMVM radio in the background.
     */
    fun playSingle(track: Track, playerViewModel: PlayerViewModel) {
        currentContext = QueueContext.SINGLE_TAP
        resetRadioState()

        // 1. Instantaneous single track playback
        playerViewModel.playTrack(track, listOf(track), autoHydrateRadio = false)

        // 2. Build online server radio in the background
        radioJob = scope.launch(Dispatchers.IO) {
            asyncBuildRadio(track, playerViewModel)
        }
    }

    /**
     * User plays an album/playlist — ordered queue, no radio until collection ends.
     */
    fun playCollection(tracks: List<Track>, startIndex: Int, playerViewModel: PlayerViewModel) {
        currentContext = QueueContext.COLLECTION
        resetRadioState()
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val selectedTrack = tracks[safeIndex]
        playerViewModel.playTrack(selectedTrack, tracks, autoHydrateRadio = false)
    }

    /** Explicit "Start Radio" action */
    fun startRadio(seedTrack: Track, playerViewModel: PlayerViewModel) {
        playSingle(seedTrack, playerViewModel)
    }

    /** Tag an active Jam session */
    fun onJamActivated() {
        currentContext = QueueContext.JAM
        resetRadioState()
    }

    private suspend fun asyncBuildRadio(seedTrack: Track, playerViewModel: PlayerViewModel) {
        val canonicalId = CanonicalSeedResolver.resolveToCanonicalId(seedTrack)
        if (canonicalId.isBlank()) return

        val (radioTracks, nextToken) = ContinuumRadioEngine.fetchRadioPage(
            videoId = canonicalId,
            continuationToken = null,
            seedTrack = seedTrack
        )

        if (radioTracks.isNotEmpty()) {
            radioVideoId = canonicalId
            radioContinuationToken = nextToken
            radioPagesFetched = 1

            withContext(Dispatchers.Main) {
                val currentState = playerViewModel.playerState.value
                val isCurrent = currentState.currentTrack?.let {
                    it.id == seedTrack.id || (it.title.equals(seedTrack.title, ignoreCase = true) && it.artist.equals(seedTrack.artist, ignoreCase = true))
                } ?: true

                if (isCurrent) {
                    val currentPlaying = currentState.currentTrack ?: seedTrack
                    val filteredRadio = radioTracks.filterNot {
                        it.title.equals(currentPlaying.title, ignoreCase = true) && it.artist.equals(currentPlaying.artist, ignoreCase = true)
                    }
                    val newQueue = listOf(currentPlaying) + filteredRadio
                    playerViewModel.updateQueueSilently(newQueue, newIndex = 0)
                }
            }
        } else {
            // Fallback to strict YTM search mix if RDAMVM yields empty
            val fallbackCandidates = OnlineRadioEngine.fetchCandidates(seedTrack, listOf(seedTrack), targetCount = 20)
            if (fallbackCandidates.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val currentState = playerViewModel.playerState.value
                    val currentPlaying = currentState.currentTrack ?: seedTrack
                    val newQueue = listOf(currentPlaying) + fallbackCandidates
                    playerViewModel.updateQueueSilently(newQueue, newIndex = 0)
                }
            }
        }
    }

    /**
     * Infinite Continuation Chaining: called by ExoPlayer transition listener.
     * Appends next page to queue whenever remaining tracks drop below minRemaining.
     */
    suspend fun ensureQueueDepth(playerViewModel: PlayerViewModel, minRemaining: Int = 5) {
        if (currentContext != QueueContext.SINGLE_TAP) return
        val currentState = playerViewModel.playerState.value
        val remaining = currentState.queue.size - (currentState.currentIndex + 1)
        if (remaining >= minRemaining) return
        val vId = radioVideoId ?: return
        val token = radioContinuationToken ?: return

        val (nextPage, newToken) = ContinuumRadioEngine.fetchRadioPage(
            videoId = vId,
            continuationToken = token,
            seedTrack = null
        )

        if (nextPage.isNotEmpty()) {
            radioContinuationToken = newToken
            radioPagesFetched++
            withContext(Dispatchers.Main) {
                val existingQueue = playerViewModel.playerState.value.queue
                val existingTitles = existingQueue.map { "${it.title}:::${it.artist}".lowercase() }.toSet()
                val newItems = nextPage.filterNot { existingTitles.contains("${it.title}:::${it.artist}".lowercase()) }
                if (newItems.isNotEmpty()) {
                    playerViewModel.appendToQueue(newItems)
                }
            }
        }
    }

    /** Seamlessly transition from finished collection to radio */
    fun onCollectionExhausted(lastTrack: Track, playerViewModel: PlayerViewModel) {
        currentContext = QueueContext.SINGLE_TAP
        resetRadioState()
        radioJob = scope.launch(Dispatchers.IO) {
            asyncBuildRadio(lastTrack, playerViewModel)
        }
    }
}
