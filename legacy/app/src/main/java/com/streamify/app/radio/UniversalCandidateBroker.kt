package com.streamify.app.radio
import com.streamify.app.data.models.Track
import com.streamify.app.radio.OnlineRadioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * QUEUE BROKER v2 — thin delegate over [OnlineRadioEngine].
 *
 * HISTORY: this broker used to fan out to Innertube + Supabase cloud-taste
 * rows + LOCAL library Markov anchors, interleave them 70/30, and — on any
 * failure — silently substitute the user's entire shuffled library as the
 * "radio". That was the source of playlists leaking into queues and of
 * intermittent builds.
 *
 * NOW: pure online construction (YTM radio → strict search-mix → Spotify
 * recommendations). Local ingestion is structurally impossible here; total
 * provider failure returns an EMPTY queue which callers surface honestly.
 *
 * The object shell (flows + signature) is preserved so every existing call
 * site keeps compiling unchanged.
 */
object UniversalCandidateBroker {

    private val _currentRecommendations = MutableStateFlow<List<Track>>(emptyList())
    val currentRecommendations: StateFlow<List<Track>> = _currentRecommendations.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    // SINGLE-FLIGHT: track transitions used to fire fetchCandidates up to
    // THREE times within milliseconds (transition + discontinuity + lookahead
    // listeners), each launching its own 3-provider network harvest. The mutex
    // serializes; the short-window key cache collapses identical bursts into
    // ONE harvest.
    private val fetchMutex = kotlinx.coroutines.sync.Mutex()
    private var lastFetchKey: Long = -1L
    private var lastFetchResult: List<Track> = emptyList()
    private var lastFetchAtMs = 0L

    private const val BURST_WINDOW_MS = 2500L

    suspend fun fetchCandidates(
        seedTrack: Track,
        activeQueue: List<Track> = emptyList(),
        targetCount: Int = 20,
        @Suppress("UNUSED_PARAMETER") allowLocalAnchors: Boolean = false
    ): List<Track> {
        // Key identifies WHAT is being built: same seed + same queue shape +
        // same target => identical request.
        val key = (seedTrack.id.toLong() shl 32) xor
                ((activeQueue.size.coerceAtMost(0xFFFF)).toLong() shl 16) xor
                targetCount.toLong()

        val now = System.currentTimeMillis()

        // Burst collapse: an identical fetch answered moments ago.
        if (key == lastFetchKey && now - lastFetchAtMs < BURST_WINDOW_MS) {
            return lastFetchResult
        }

        return fetchMutex.withLock {
            // Re-check inside the lock: another caller may have just finished
            // this exact fetch while we waited.
            val innerNow = System.currentTimeMillis()
            if (key == lastFetchKey && innerNow - lastFetchAtMs < BURST_WINDOW_MS) {
                return@withLock lastFetchResult
            }

            _isFetching.value = true
            try {
                val result = OnlineRadioEngine.fetchCandidates(
                    seedTrack = seedTrack,
                    activeQueue = activeQueue,
                    targetCount = targetCount
                )
                lastFetchKey = key
                lastFetchResult = result
                lastFetchAtMs = System.currentTimeMillis()
                _currentRecommendations.value = result
                result
            } finally {
                _isFetching.value = false
            }
        }
    }
}
