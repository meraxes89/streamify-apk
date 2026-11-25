package com.maxrave.simpmusic.player

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLAudioElement
import com.maxrave.simpmusic.di.StoredWebSong
import com.maxrave.simpmusic.di.parseDurationStringToSeconds
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.extension.now
import com.maxrave.common.Config
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.utils.Resource
import org.koin.core.context.GlobalContext

class WebMediaPlayerHandler : MediaPlayerHandler {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioElement: HTMLAudioElement = document.createElement("audio") as HTMLAudioElement

    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(null)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData = MutableStateFlow<QueueData?>(null)
    override val queueData: StateFlow<QueueData?> = _queueData.asStateFlow()

    private val _controlState = MutableStateFlow(
        ControlState(
            isPlaying = false,
            isShuffle = false,
            repeatState = RepeatState.None,
            isLiked = false,
            isNextAvailable = false,
            isPreviousAvailable = false,
            isCrossfading = false,
            volume = 1f,
        ),
    )
    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState(isDone = false, timeRemaining = 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _skipSegments = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format = MutableStateFlow<NewFormatEntity?>(null)
    override val format: StateFlow<NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex = MutableStateFlow(0)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _castState = MutableStateFlow(GenericCastState(isRemote = false, deviceName = null))
    override val castState: StateFlow<GenericCastState> = _castState.asStateFlow()

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}

    fun getResolvedDuration(): Long {
        val audioDur = audioElement.duration
        if (!audioDur.isNaN() && audioDur > 0.0 && audioDur < 1e9) {
            return (audioDur * 1000).toLong()
        }
        val curSong = _nowPlayingState.value.songEntity
        if (curSong != null && curSong.durationSeconds > 0) {
            return curSong.durationSeconds * 1000L
        }
        val curTrack = _nowPlayingState.value.track
        if (curTrack != null) {
            val trackSec = curTrack.durationSeconds ?: 0
            if (trackSec > 0) {
                return trackSec * 1000L
            }
            val parsed = parseDurationStringToSeconds(curTrack.duration)
            if (parsed > 0) return parsed * 1000L
        }
        return 0L
    }

    override val player: MediaPlayerInterface = object : MediaPlayerInterface {
        override fun play() {
            try {
                audioElement.play().catch { error ->
                    Logger.w("WebMediaPlayerHandler", "Playback play() promise rejected: $error")
                    _controlState.value = _controlState.value.copy(isPlaying = false)
                    null
                }
                _controlState.value = _controlState.value.copy(isPlaying = !audioElement.paused)
            } catch (e: Throwable) {
                Logger.w("WebMediaPlayerHandler", "Playback play() exception: ${e.message}")
                _controlState.value = _controlState.value.copy(isPlaying = false)
            }
        }
        override fun pause() {
            audioElement.pause()
            _controlState.value = _controlState.value.copy(isPlaying = false)
        }
        override fun stop() {
            audioElement.pause()
            audioElement.currentTime = 0.0
            _controlState.value = _controlState.value.copy(isPlaying = false)
        }
        override fun seekTo(positionMs: Long) {
            val total = getResolvedDuration()
            val targetMs = if (total > 0L) positionMs.coerceIn(0L, total) else positionMs.coerceAtLeast(0L)
            audioElement.currentTime = targetMs / 1000.0
            _simpleMediaState.value = SimpleMediaState.Progress(targetMs)
        }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            playMediaItemInMediaSource(mediaItemIndex)
            if (positionMs > 0) seekTo(positionMs)
        }
        override fun seekBack() {
            val cur = (audioElement.currentTime * 1000).toLong()
            seekTo((cur - 5000L).coerceAtLeast(0L))
        }
        override fun seekForward() {
            val cur = (audioElement.currentTime * 1000).toLong()
            val total = getResolvedDuration()
            val target = cur + 5000L
            seekTo(if (total > 0L) target.coerceAtMost(total) else target)
        }
        override fun seekToNext() {
            this@WebMediaPlayerHandler.seekToNext()
        }
        override fun seekToPrevious() {
            this@WebMediaPlayerHandler.seekToPrevious()
        }
        override fun seekToPreviousMediaItem() {
            this@WebMediaPlayerHandler.seekToPrevious()
        }
        override fun prepare() {}

        override fun setMediaItem(mediaItem: GenericMediaItem) {
            this@WebMediaPlayerHandler.addMediaItem(mediaItem, playWhenReady = true)
        }
        override fun addMediaItem(mediaItem: GenericMediaItem) {
            this@WebMediaPlayerHandler.addMediaItem(mediaItem, playWhenReady = false)
        }
        override fun addMediaItem(index: Int, mediaItem: GenericMediaItem) {
            this@WebMediaPlayerHandler.addMediaItem(mediaItem, playWhenReady = false)
        }
        override fun removeMediaItem(index: Int) {
            this@WebMediaPlayerHandler.removeMediaItem(index)
        }
        override fun removeMediaItems(fromIndex: Int, toIndex: Int, onRemoved: (removedIds: List<String>) -> Unit) {
            onRemoved(emptyList())
        }
        override fun moveMediaItem(fromIndex: Int, toIndex: Int) {
            scope.launch { swap(fromIndex, toIndex) }
        }
        override fun clearMediaItems() {
            _nowPlaying.value = null
        }
        override fun replaceMediaItem(index: Int, mediaItem: GenericMediaItem) {}
        override fun getMediaItemAt(index: Int): GenericMediaItem? =
            _queueData.value?.data?.listTracks?.getOrNull(index)?.toGenericMediaItem() ?: _nowPlaying.value
        override fun getCurrentMediaTimeLine(): List<GenericMediaItem> =
            _queueData.value?.data?.listTracks?.map { it.toGenericMediaItem() } ?: _nowPlaying.value?.let { listOf(it) } ?: emptyList()
        override fun getUnshuffledIndex(shuffledIndex: Int): Int = shuffledIndex

        override val isPlaying: Boolean get() = !audioElement.paused
        override val currentPosition: Long get() = (audioElement.currentTime * 1000).toLong()
        override val duration: Long get() = getResolvedDuration()
        override val bufferedPosition: Long get() = currentPosition
        override val bufferedPercentage: Int get() = 100
        override val currentMediaItem: GenericMediaItem? get() = _nowPlaying.value
        override val currentMediaItemIndex: Int get() = _currentSongIndex.value
        override val mediaItemCount: Int get() = _queueData.value?.data?.listTracks?.size ?: if (_nowPlaying.value != null) 1 else 0
        override val contentPosition: Long get() = currentPosition
        override val playbackState: Int get() = 3

        override fun hasNextMediaItem(): Boolean =
            (_currentSongIndex.value + 1) < (_queueData.value?.data?.listTracks?.size ?: 0)
        override fun hasPreviousMediaItem(): Boolean =
            _currentSongIndex.value > 0

        override var shuffleModeEnabled: Boolean = false
        override var repeatMode: Int = 0
        override var playWhenReady: Boolean = true
        override var playbackParameters: GenericPlaybackParameters = GenericPlaybackParameters(1f, 1f)

        override val audioSessionId: Int get() = 0
        override var volume: Float
            get() = audioElement.volume.toFloat()
            set(value) {
                audioElement.volume = value.toDouble().coerceIn(0.0, 1.0)
            }
        override var sleepFadeFactor: Float = 1f
        override var crossfadeSuppressed: Boolean = false
        override var albumTrackIds: Set<String> = emptySet()
        override var skipSilenceEnabled: Boolean = false

        override fun addListener(listener: MediaPlayerListener) {}
        override fun removeListener(listener: MediaPlayerListener) {}
        override fun release() {
            audioElement.pause()
        }
    }

    init {
        audioElement.onerror = { _, _, _, _, _ ->
            val errCode = audioElement.error?.code?.toInt() ?: 0
            val errName = when (errCode) {
                1 -> "MEDIA_ERR_ABORTED"
                2 -> "MEDIA_ERR_NETWORK"
                3 -> "MEDIA_ERR_DECODE"
                4 -> "MEDIA_ERR_SRC_NOT_SUPPORTED"
                else -> "UNKNOWN_MEDIA_ERROR"
            }
            pushPlayerError(
                PlayerError(
                    errorCode = 404,
                    errorCodeName = errName,
                    message = "Audio playback failed: $errName (code $errCode)",
                ),
            )
            _simpleMediaState.value = SimpleMediaState.Initial
            null
        }
        audioElement.onended = {
            seekToNext()
        }
        audioElement.onloadedmetadata = {
            val dur = getResolvedDuration()
            if (dur > 0L) {
                _simpleMediaState.value = SimpleMediaState.Ready(dur)
            }
        }
        audioElement.oncanplay = {
            val dur = getResolvedDuration()
            if (dur > 0L) {
                _simpleMediaState.value = SimpleMediaState.Ready(dur)
            }
        }
        audioElement.onwaiting = {
            _simpleMediaState.value = SimpleMediaState.Buffering(0L)
        }
        audioElement.onplaying = {
            val dur = getResolvedDuration()
            if (dur > 0L) {
                _simpleMediaState.value = SimpleMediaState.Ready(dur)
            }
            _controlState.value = _controlState.value.copy(isPlaying = true)
        }
        audioElement.onpause = {
            _controlState.value = _controlState.value.copy(isPlaying = false)
        }
        audioElement.ontimeupdate = {
            val curMs = (audioElement.currentTime * 1000).toLong()
            _simpleMediaState.value = SimpleMediaState.Progress(curMs)
        }
        window.setInterval({
            if (!audioElement.paused) {
                val currentMs = (audioElement.currentTime * 1000).toLong()
                _simpleMediaState.value = SimpleMediaState.Progress(currentMs)
            }
            null
        }, 250)
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            is PlayerEvent.PlayPause -> {
                if (audioElement.paused) player.play() else player.pause()
            }
            is PlayerEvent.Next -> player.seekToNext()
            is PlayerEvent.Previous -> player.seekToPrevious()
            is PlayerEvent.SkipToPrevious -> player.seekToPreviousMediaItem()
            is PlayerEvent.Shuffle -> {
                val nextShuffle = !_controlState.value.isShuffle
                player.shuffleModeEnabled = nextShuffle
                _controlState.value = _controlState.value.copy(isShuffle = nextShuffle)
            }
            is PlayerEvent.Repeat -> {}
            is PlayerEvent.UpdateProgress -> {
                val dur = getResolvedDuration()
                if (dur > 0L) {
                    val targetMs = ((playerEvent.newProgress / 100f) * dur).toLong().coerceIn(0L, dur)
                    player.seekTo(targetMs)
                }
            }
            is PlayerEvent.ToggleLike -> toggleLike()
            is PlayerEvent.UpdateVolume -> {
                player.volume = playerEvent.newVolume
                _controlState.value = _controlState.value.copy(volume = playerEvent.newVolume)
            }
            is PlayerEvent.Backward -> player.seekBack()
            is PlayerEvent.Forward -> player.seekForward()
            is PlayerEvent.Stop -> player.stop()
        }
    }

    override fun toggleRadio() {}
    override fun toggleLike() {
        val next = !_controlState.value.isLiked
        like(next)
    }
    override fun like(liked: Boolean) {
        _controlState.value = _controlState.value.copy(isLiked = liked)
        val curSong = _nowPlayingState.value.songEntity ?: return
        val updated = curSong.copy(
            liked = liked,
            likeStatus = if (liked) "LIKE" else "INDIFFERENT",
            favoriteAt = if (liked) now() else null
        )
        _nowPlayingState.value = _nowPlayingState.value.copy(songEntity = updated)
        scope.launch {
            try {
                val songRepo = GlobalContext.get().get<SongRepository>()
                songRepo.insertSong(updated).firstOrNull()
                songRepo.updateLikeStatus(updated.videoId, if (liked) 1 else 0)
            } catch (_: Throwable) {}
        }
    }
    override fun resetSongAndQueue() {
        player.stop()
        _nowPlaying.value = null
        _nowPlayingState.value = NowPlayingTrackState.initial()
    }
    private fun updateControls() {
        val queue = _queueData.value?.data?.listTracks ?: emptyList()
        val idx = _currentSongIndex.value
        _controlState.value = _controlState.value.copy(
            isNextAvailable = idx + 1 < queue.size,
            isPreviousAvailable = idx > 0
        )
    }

    override fun sleepStart(minutes: Int) {}
    override fun sleepStop() {}

    override fun removeMediaItem(position: Int) {
        val queue = _queueData.value?.data?.listTracks?.toMutableList() ?: return
        if (position in 0 until queue.size) {
            queue.removeAt(position)
            _queueData.value = _queueData.value?.copy(
                data = _queueData.value!!.data.copy(listTracks = queue)
            )
            if (_currentSongIndex.value >= queue.size) {
                _currentSongIndex.value = (queue.size - 1).coerceAtLeast(0)
            }
            updateControls()
        }
    }

    override fun addMediaItem(mediaItem: GenericMediaItem, playWhenReady: Boolean) {
        _nowPlaying.value = mediaItem
        val currentTrack = _queueData.value?.data?.listTracks?.firstOrNull { it.videoId == mediaItem.mediaId }
        val currentSong = currentTrack?.toSongEntity()
        val qIdx = _queueData.value?.data?.listTracks?.indexOfFirst { it.videoId == mediaItem.mediaId } ?: -1
        if (qIdx != -1) {
            _currentSongIndex.value = qIdx
        }
        val isLiked = try {
            val json = kotlinx.browser.localStorage.getItem("streamify_songs")
            json != null && json.contains("\"videoId\":\"${mediaItem.mediaId}\"") && json.contains("\"liked\":true")
        } catch (_: Throwable) {
            false
        }
        val trackSec = currentTrack?.durationSeconds ?: 0
        val durSec: Int = if (trackSec > 0) trackSec else parseDurationStringToSeconds(currentTrack?.duration)
        val fixedTrack = if (currentTrack != null && trackSec <= 0 && durSec > 0) currentTrack.copy(durationSeconds = durSec) else currentTrack
        val songEnt = (fixedTrack?.toSongEntity() ?: currentSong)?.copy(liked = isLiked, durationSeconds = durSec)
        _controlState.value = _controlState.value.copy(isLiked = isLiked)
        _nowPlayingState.value = NowPlayingTrackState(
            mediaItem = mediaItem,
            track = fixedTrack ?: currentTrack,
            songEntity = songEnt,
        )
        if (durSec > 0) {
            _simpleMediaState.value = SimpleMediaState.Ready(durSec * 1000L)
        }
        val uri = mediaItem.uri
        val streamUrl = if (!uri.isNullOrBlank() && uri != mediaItem.mediaId) {
            uri
        } else {
            "/api/stream?id=" + mediaItem.mediaId
        }
        audioElement.src = streamUrl
        if (playWhenReady) {
            player.play()
        }
        updateControls()

        try {
            if (currentSong != null) {
                val json = kotlinx.browser.localStorage.getItem("streamify_songs")
                val list = if (!json.isNullOrEmpty()) {
                    Json { ignoreUnknownKeys = true }.decodeFromString<List<StoredWebSong>>(json).toMutableList()
                } else {
                    mutableListOf()
                }
                val idx = list.indexOfFirst { it.videoId == currentSong.videoId }
                if (idx != -1) {
                    val old = list[idx]
                    list[idx] = old.copy(totalPlayTime = old.totalPlayTime + 180)
                } else {
                    list.add(
                        StoredWebSong(
                            videoId = currentSong.videoId,
                            title = currentSong.title,
                            artistName = currentSong.artistName?.firstOrNull() ?: "",
                            albumName = currentSong.albumName ?: "",
                            duration = currentSong.duration,
                            durationSeconds = currentSong.durationSeconds,
                            thumbnails = currentSong.thumbnails ?: "",
                            liked = false,
                            totalPlayTime = 180,
                        )
                    )
                }
                kotlinx.browser.localStorage.setItem("streamify_songs", Json.encodeToString(list))
            }
        } catch (_: Throwable) {}
    }

    override fun clearMediaItems() {
        _nowPlaying.value = null
    }
    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        mediaItemList.firstOrNull()?.let { addMediaItem(it, playWhenReady = false) }
    }
    override fun playMediaItemInMediaSource(index: Int) {
        val queue = _queueData.value?.data?.listTracks ?: return
        if (index in 0 until queue.size) {
            _currentSongIndex.value = index
            val track = queue[index]
            scope.launch {
                loadMediaItem(track, type = Config.SONG_CLICK, index = index)
            }
        }
    }
    override fun currentSongIndex(): Int = _currentSongIndex.value
    override fun currentOrderIndex(): Int = _currentSongIndex.value
    override suspend fun swap(from: Int, to: Int) {
        val queue = _queueData.value?.data?.listTracks?.toMutableList() ?: return
        if (from in 0 until queue.size && to in 0 until queue.size) {
            val item = queue.removeAt(from)
            queue.add(to, item)
            _queueData.value = _queueData.value?.copy(
                data = _queueData.value!!.data.copy(listTracks = queue)
            )
        }
    }
    override fun resetCrossfade() {}

    override fun shufflePlaylist(randomTrackIndex: Int) {
        val queue = _queueData.value?.data?.listTracks ?: return
        if (queue.size <= 1) return
        val currentTrack = queue.getOrNull(randomTrackIndex) ?: queue[_currentSongIndex.value]
        val otherTracks = queue.filter { it.videoId != currentTrack.videoId }.shuffled()
        val newQueue = ArrayList(listOf(currentTrack) + otherTracks)
        _currentSongIndex.value = 0
        _queueData.value = _queueData.value?.copy(
            data = _queueData.value!!.data.copy(listTracks = newQueue)
        )
        updateControls()
    }

    override fun loadMore() {
        val cont = _queueData.value?.data?.continuation
        val pId = _queueData.value?.data?.playlistId ?: ""
        if (cont.isNullOrEmpty()) return
        scope.launch {
            try {
                val songRepo = GlobalContext.get().get<SongRepository>()
                songRepo.getContinueTrack(pId, cont, false).collect { pair ->
                    val tracks = pair.first
                    val nextCont = pair.second
                    if (!tracks.isNullOrEmpty()) {
                        loadMoreCatalog(tracks)
                        _queueData.value = _queueData.value?.let { q ->
                            q.copy(
                                data = q.data.copy(continuation = nextCont)
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun getRelated(videoId: String) {
        if (_queueData.value?.queueState == QueueData.StateSource.STATE_INITIALIZING) return
        scope.launch {
            try {
                val songRepo = GlobalContext.get().get<SongRepository>()
                songRepo.getRelatedData(videoId).collect { response ->
                    when (response) {
                        is Resource.Success -> {
                            val tracks = response.data?.first ?: emptyList()
                            val continuation = response.data?.second
                            loadMoreCatalog(ArrayList(tracks))
                            _queueData.value = _queueData.value?.let { q ->
                                q.copy(
                                    data = q.data.copy(continuation = continuation)
                                )
                            }
                        }
                        is Resource.Error -> {}
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun setQueueData(queueData: QueueData.Data) {
        _queueData.value = QueueData(
            queueState = QueueData.StateSource.STATE_INITIALIZED,
            data = queueData
        )
        val idx = if (queueData.firstPlayedTrack != null) {
            queueData.listTracks.indexOfFirst { it.videoId == queueData.firstPlayedTrack?.videoId }.coerceAtLeast(0)
        } else 0
        _currentSongIndex.value = idx
        updateControls()
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = _nowPlaying.value

    override suspend fun moveItemUp(position: Int) {
        if (position > 0) swap(position, position - 1)
    }
    override suspend fun moveItemDown(position: Int) {
        val size = _queueData.value?.data?.listTracks?.size ?: 0
        if (position < size - 1) swap(position, position + 1)
    }
    override suspend fun moveItemToPlayNext(position: Int) {
        val curIdx = _currentSongIndex.value
        val queue = _queueData.value?.data?.listTracks?.toMutableList() ?: return
        if (position in 0 until queue.size) {
            val item = queue.removeAt(position)
            val nextIdx = (curIdx + 1).coerceAtMost(queue.size)
            queue.add(nextIdx, item)
            _queueData.value = _queueData.value?.copy(
                data = _queueData.value!!.data.copy(listTracks = queue)
            )
        }
    }
    override fun addFirstMediaItemToIndex(mediaItem: GenericMediaItem?, index: Int) {}
    override fun reset() {}
    override suspend fun load(downloaded: Int, index: Int?) {
        loadPlaylistOrAlbum(index)
    }

    override suspend fun loadMoreCatalog(listTrack: ArrayList<Track>, isAddToQueue: Boolean) {
        val existing = _queueData.value?.data?.listTracks ?: arrayListOf()
        val existingIds = existing.map { it.videoId }.toSet()
        val toAdd = listTrack.filter { it.videoId !in existingIds }
        val combined = ArrayList(existing + toAdd)
        val curData = _queueData.value?.data ?: QueueData.Data(listTracks = combined)
        _queueData.value = QueueData(
            queueState = QueueData.StateSource.STATE_INITIALIZED,
            data = curData.copy(listTracks = combined)
        )
        updateControls()
    }

    override suspend fun updateCatalog(downloaded: Int, index: Int?): Boolean = true
    override fun addQueueToPlayer() {}

    override fun loadPlaylistOrAlbum(index: Int?) {
        val queue = _queueData.value?.data?.listTracks ?: return
        val targetIdx = (index ?: 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        if (targetIdx in 0 until queue.size) {
            _currentSongIndex.value = targetIdx
            val track = queue[targetIdx]
            scope.launch {
                loadMediaItem(track, type = Config.PLAYLIST_CLICK, index = targetIdx)
            }
        }
    }

    override fun setCurrentSongIndex(index: Int) {
        _currentSongIndex.value = index
        updateControls()
    }

    override suspend fun playNext(track: Track) {
        val queue = _queueData.value?.data?.listTracks?.toMutableList() ?: mutableListOf()
        val curIdx = _currentSongIndex.value
        val nextIdx = (curIdx + 1).coerceAtMost(queue.size)
        queue.add(nextIdx, track)
        _queueData.value = _queueData.value?.copy(
            data = (_queueData.value?.data ?: QueueData.Data()).copy(listTracks = queue)
        )
        updateControls()
    }

    fun seekToNext() {
        val queue = _queueData.value?.data?.listTracks ?: return
        val currentIdx = _currentSongIndex.value
        val nextIdx = currentIdx + 1
        if (nextIdx < queue.size) {
            _currentSongIndex.value = nextIdx
            val nextTrack = queue[nextIdx]
            scope.launch {
                loadMediaItem(nextTrack, type = Config.SONG_CLICK, index = nextIdx)
            }
            if (nextIdx >= queue.size - 3) {
                loadMore()
            }
        }
    }

    fun seekToPrevious() {
        if (audioElement.currentTime > 5.0) {
            audioElement.currentTime = 0.0
            return
        }
        val queue = _queueData.value?.data?.listTracks ?: return
        val currentIdx = _currentSongIndex.value
        val prevIdx = currentIdx - 1
        if (prevIdx >= 0 && prevIdx < queue.size) {
            _currentSongIndex.value = prevIdx
            val prevTrack = queue[prevIdx]
            scope.launch {
                loadMediaItem(prevTrack, type = Config.SONG_CLICK, index = prevIdx)
            }
        } else {
            audioElement.currentTime = 0.0
        }
    }

    override suspend fun <T> loadMediaItem(anyTrack: T, type: String, index: Int?) {
        val track = when (anyTrack) {
            is Track -> anyTrack
            is SongEntity -> anyTrack.toTrack()
            else -> return
        }
        val mediaItem = track.toGenericMediaItem()
        _nowPlaying.value = mediaItem
        if (index != null && index >= 0) {
            _currentSongIndex.value = index
        } else {
            val qIdx = _queueData.value?.data?.listTracks?.indexOfFirst { it.videoId == track.videoId } ?: -1
            if (qIdx != -1) {
                _currentSongIndex.value = qIdx
            }
        }
        val isLiked = try {
            val json = kotlinx.browser.localStorage.getItem("streamify_songs")
            json != null && json.contains("\"videoId\":\"${track.videoId}\"") && json.contains("\"liked\":true")
        } catch (_: Throwable) {
            false
        }
        val trackSec = track.durationSeconds ?: 0
        val durSec: Int = if (trackSec > 0) trackSec else parseDurationStringToSeconds(track.duration)
        val fixedTrack = if (trackSec <= 0 && durSec > 0) track.copy(durationSeconds = durSec) else track
        val songEnt = fixedTrack.toSongEntity().copy(liked = isLiked, durationSeconds = durSec)
        _controlState.value = _controlState.value.copy(isLiked = isLiked)
        _nowPlayingState.value = NowPlayingTrackState(
            mediaItem = mediaItem,
            track = fixedTrack,
            songEntity = songEnt,
        )
        if (durSec > 0) {
            _simpleMediaState.value = SimpleMediaState.Ready(durSec * 1000L)
        }
        audioElement.src = "/api/stream?id=" + fixedTrack.videoId
        player.play()
        updateControls()

        try {
            val currentSong = track.toSongEntity()
            val json = kotlinx.browser.localStorage.getItem("streamify_songs")
            val list = if (!json.isNullOrEmpty()) {
                Json { ignoreUnknownKeys = true }.decodeFromString<List<StoredWebSong>>(json).toMutableList()
            } else {
                mutableListOf()
            }
            val sIdx = list.indexOfFirst { it.videoId == currentSong.videoId }
            if (sIdx != -1) {
                val old = list[sIdx]
                list[sIdx] = old.copy(totalPlayTime = old.totalPlayTime + 180)
            } else {
                list.add(
                    StoredWebSong(
                        videoId = currentSong.videoId,
                        title = currentSong.title,
                        artistName = currentSong.artistName?.firstOrNull() ?: "",
                        albumName = currentSong.albumName ?: "",
                        duration = currentSong.duration,
                        durationSeconds = currentSong.durationSeconds,
                        thumbnails = currentSong.thumbnails ?: "",
                        liked = false,
                        totalPlayTime = 180,
                    )
                )
            }
            kotlinx.browser.localStorage.setItem("streamify_songs", Json.encodeToString(list))
        } catch (_: Throwable) {}
    }
    override fun getPlayerDuration(): Long = player.duration
    override fun getProgress(): Long = player.currentPosition
    override fun startProgressUpdate() {}
    override fun stopProgressUpdate() {}
    override fun startBufferedUpdate() {}
    override fun stopBufferedUpdate() {}
    override fun mayBeNormalizeVolume() {}
    override fun mayBeSaveRecentSong(runBlocking: Boolean) {}
    override fun mayBeSavePlaybackState() {}
    override fun mayBeRestoreQueue() {}
    override suspend fun restoreQueueAndPlay(): Boolean = false
    override fun shouldReleaseOnTaskRemoved(): Boolean = false
    override fun release() {
        player.release()
    }
}
