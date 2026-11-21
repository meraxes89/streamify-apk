package com.maxrave.simpmusic.di

import androidx.paging.PagingData
import com.maxrave.domain.data.entities.*
import com.maxrave.domain.data.model.browse.album.AlbumBrowse
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistEditorial
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.browse.playlist.PlaylistState
import com.maxrave.domain.data.model.canvas.CanvasResult
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsPage
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.home.BrowsePage
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.data.model.listentogether.ListenTogetherRoom
import com.maxrave.domain.data.model.listentogether.RoomTrack
import com.maxrave.domain.data.model.lyrics.RomanizationDictionaryState
import com.maxrave.domain.data.model.lyrics.RomanizationLanguage
import com.maxrave.domain.data.model.searchResult.SearchSuggestions
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.FeedbackTokens
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.data.type.*
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.*
import com.maxrave.domain.utils.FilterState
import com.maxrave.domain.utils.LocalResource
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.YouTubeLikeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.browser.localStorage
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.data.model.library.LibraryOverview
import com.maxrave.domain.data.model.library.LibraryCollectionPreview
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.model.analytics.AnalyticsPeriodStats
import com.maxrave.domain.data.entities.analytics.query.TopPlayedAlbum
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtist
import com.maxrave.domain.data.entities.analytics.query.TopPlayedArtistTime
import com.maxrave.domain.data.entities.analytics.query.TopPlayedTracks
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.account.AccountInfo
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.PodcastWithEpisodes
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.model.podcast.PodcastBrowse
import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.model.autoeq.AutoEqCurve
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.data.model.cookie.CookieItem
import com.maxrave.domain.repository.ImportProgress
import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteProfile
import com.maxrave.domain.data.model.loginsync.LoginSyncHostState
import com.maxrave.domain.data.model.loginsync.NetAddress
import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.librarysync.LibrarySyncPairingState
import com.maxrave.domain.data.model.librarysync.ReceivedLogins
import com.maxrave.domain.data.model.librarysync.SyncPeer

private fun sanitizeThumbnailUrl(rawUrl: String): String {
    if (rawUrl.isBlank()) return ""
    var url = rawUrl
    if (url.startsWith("//")) {
        url = "https:" + url
    }
    if (url.startsWith("/")) {
        val origin = try {
            kotlinx.browser.window.location.origin
        } catch (_: Throwable) {
            "http://localhost:3000"
        }
        url = origin.trimEnd('/') + url
    }
    return url
}

fun parseDurationStringToSeconds(durationStr: String?): Int {
    if (durationStr.isNullOrBlank()) return 0
    val parts = durationStr.trim().split(":")
    return try {
        when (parts.size) {
            1 -> parts[0].toIntOrNull() ?: 0
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    } catch (_: Throwable) {
        0
    }
}

private fun urlEncode(s: String): String {
    return buildString {
        for (ch in s) {
            if (ch.isLetterOrDigit() || ch in "-_.~") {
                append(ch)
            } else if (ch == ' ') {
                append("+")
            } else {
                append('%')
                val hex = ch.code.toString(16).uppercase()
                if (hex.length == 1) append('0')
                append(hex)
            }
        }
    }
}

private fun getLocalStorage(key: String): String? = try {
    localStorage.getItem(key)
} catch (_: Throwable) {
    null
}

private fun setLocalStorage(key: String, value: String) {
    try {
        localStorage.setItem(key, value)
    } catch (_: Throwable) {}
}

@Serializable
data class StoredWebSong(
    val videoId: String,
    val title: String,
    val artistName: String = "",
    val albumName: String = "",
    val duration: String = "",
    val durationSeconds: Int = 0,
    val thumbnails: String = "",
    val liked: Boolean = false,
    val totalPlayTime: Long = 0,
)

fun StoredWebSong.toEntity() = SongEntity(
    videoId = videoId,
    title = title,
    artistName = if (artistName.isNotEmpty()) listOf(artistName) else emptyList(),
    albumName = albumName.takeIf { it.isNotEmpty() },
    duration = duration,
    durationSeconds = durationSeconds,
    thumbnails = thumbnails.takeIf { it.isNotEmpty() },
    liked = liked,
    totalPlayTime = totalPlayTime,
    isAvailable = true,
    isExplicit = false,
    likeStatus = if (liked) "LIKE" else "INDIFFERENT",
    videoType = "MUSIC_VIDEO_TYPE_ATV",
    category = "Song",
    resultType = "Song",
)

@Serializable
data class StoredWebPlaylist(
    val id: Long,
    val title: String,
    val thumbnail: String? = null,
    val tracks: List<String> = emptyList(),
)

fun StoredWebPlaylist.toEntity() = LocalPlaylistEntity(
    id = id,
    title = title,
    thumbnail = thumbnail,
    tracks = tracks,
)

class FakeSearchRepository : SearchRepository {
    override fun getSearchHistory(): Flow<List<SearchHistory>> = flowOf(emptyList())
    override fun insertSearchHistory(searchHistory: SearchHistory): Flow<Long> = flowOf(0L)
    override suspend fun deleteSearchHistory() {}
    override fun getSearchDataSong(query: String): Flow<Resource<ArrayList<SongsResult>>> = flow {
        runCatching {
            val resText = webKtorClient.get("/api/yt/search?q=" + query).bodyAsText()
            val list = ArrayList<SongsResult>()
            val jsonArr = Json.parseToJsonElement(resText).jsonArray
            for (elem in jsonArr) {
                val obj = elem.jsonObject
                val videoId = obj["videoId"]?.jsonPrimitive?.content ?: continue
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val artists = obj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val album = obj["album"]?.let { albElem ->
                    if (albElem is kotlinx.serialization.json.JsonObject) {
                        Album(
                            id = albElem["id"]?.jsonPrimitive?.content ?: "",
                            name = albElem["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } else null
                }
                val duration = obj["duration"]?.jsonPrimitive?.content ?: ""
                val thumbnails = obj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                list.add(
                    SongsResult(
                        album = album,
                        artists = artists,
                        category = "Song",
                        duration = duration,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        feedbackTokens = null,
                        isExplicit = false,
                        resultType = "Song",
                        thumbnails = thumbnails,
                        title = title,
                        videoId = videoId,
                        videoType = null,
                        year = ""
                    )
                )
            }
            emit(Resource.Success<ArrayList<SongsResult>>(list))
        }.onFailure { err ->
            emit(Resource.Error<ArrayList<SongsResult>>(err.message ?: "Search failed"))
        }
    }
    override fun getSearchDataVideo(query: String): Flow<Resource<ArrayList<VideosResult>>> = flowOf(Resource.Success(ArrayList()))
    override fun getSearchDataPodcast(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> = flowOf(Resource.Success(ArrayList()))
    override fun getSearchDataFeaturedPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> = flowOf(Resource.Success(ArrayList()))
    override fun getSearchDataArtist(query: String): Flow<Resource<ArrayList<ArtistsResult>>> = flowOf(Resource.Success(ArrayList()))
    override fun getSearchDataAlbum(query: String): Flow<Resource<ArrayList<AlbumsResult>>> = flowOf(Resource.Success(ArrayList()))
    override fun getSearchDataPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> = flowOf(Resource.Success(ArrayList()))
    override fun getSuggestQuery(query: String): Flow<Resource<SearchSuggestions>> = flow {
        runCatching {
            val resText = webKtorClient.get("/api/yt/suggest?q=" + query).bodyAsText()
            val jsonArr = Json.parseToJsonElement(resText).jsonArray
            val queries = jsonArr.map { it.jsonPrimitive.content }
            emit(Resource.Success(SearchSuggestions(queries = queries, recommendedItems = emptyList())))
        }.onFailure {
            emit(Resource.Error("Suggest failed"))
        }
    }
}

class FakeHomeRepository : HomeRepository {
    override fun getHomeData(params: String?, viewString: String, songString: String): Flow<Resource<Triple<String?, List<HomeItem>, String?>>> = flow {
        runCatching {
            val resText = webKtorClient.get("/api/yt/home").bodyAsText()
            val rootObj = Json.parseToJsonElement(resText).jsonObject
            val itemsArr = rootObj["items"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            val continuation = rootObj["continuation"]?.jsonPrimitive?.content
            val homeItems = ArrayList<HomeItem>()

            for (shelfElem in itemsArr) {
                val shelfObj = shelfElem.jsonObject
                val title = shelfObj["title"]?.jsonPrimitive?.content ?: "Featured"
                val itemsPerColumn = shelfObj["itemsPerColumn"]?.jsonPrimitive?.intOrNull
                val contentsArr = shelfObj["contents"]?.jsonArray ?: continue
                val contentsList = ArrayList<com.maxrave.domain.data.model.home.Content>()

                for (cElem in contentsArr) {
                    val cObj = cElem.jsonObject
                    val itemTitle = cObj["title"]?.jsonPrimitive?.content ?: ""
                    val itemDesc = cObj["description"]?.jsonPrimitive?.content
                    val videoId = cObj["videoId"]?.jsonPrimitive?.content
                    val browseId = cObj["browseId"]?.jsonPrimitive?.content
                    val playlistId = cObj["playlistId"]?.jsonPrimitive?.content
                    val thumbs = cObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                        val thumbObj = thumbElem.jsonObject
                        Thumbnail(
                            height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                            url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                            width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                        )
                    } ?: emptyList<Thumbnail>()
                    val artists = cObj["artists"]?.jsonArray?.map { artElem ->
                        val artObj = artElem.jsonObject
                        com.maxrave.domain.data.model.searchResult.songs.Artist(
                            id = artObj["id"]?.jsonPrimitive?.content,
                            name = artObj["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()

                    contentsList.add(
                        com.maxrave.domain.data.model.home.Content(
                            album = null,
                            artists = artists,
                            description = itemDesc,
                            isExplicit = false,
                            playlistId = playlistId,
                            browseId = browseId,
                            thumbnails = thumbs,
                            title = itemTitle,
                            videoId = videoId,
                            views = null,
                            durationSeconds = null,
                            radio = null,
                            videoType = null,
                            isLive = false,
                            playEndpoint = null
                        )
                    )
                }

                if (contentsList.isNotEmpty()) {
                    homeItems.add(
                        HomeItem(
                            contents = contentsList,
                            title = title,
                            subtitle = null,
                            thumbnail = contentsList.firstOrNull()?.thumbnails,
                            channelId = null,
                            moreEndpoint = null,
                            itemsPerColumn = itemsPerColumn,
                            playAllEndpoint = null
                        )
                    )
                }
            }
            emit(Resource.Success(Triple(continuation, homeItems, null)))
        }.onFailure { err ->
            emit(Resource.Error(err.message ?: "Failed to fetch home"))
        }
    }
    override fun getHomeDataContinue(continueParam: String, viewString: String, songString: String): Flow<Resource<Pair<String?, List<HomeItem>>>> = flowOf(Resource.Error("None"))
    override fun getNewRelease(newReleaseString: String, musicVideoString: String): Flow<Resource<List<HomeItem>>> = flowOf(Resource.Success(emptyList()))
    override fun getChartData(countryCode: String): Flow<Resource<Chart>> = flowOf(Resource.Error("None"))
    override fun getMoodAndMomentsData(): Flow<Resource<Mood>> = flowOf(Resource.Error("None"))
    override fun getMoodCategoryArtwork(params: String): Flow<String?> = flowOf(null)
    override fun getGenreData(params: String): Flow<Resource<GenreObject>> = flowOf(Resource.Error("None"))
    override fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>> = flowOf(Resource.Error("None"))
    override fun getBrowsePage(browseId: String, params: String?, songString: String): Flow<Resource<BrowsePage>> = flowOf(Resource.Error("None"))
}

class FakeUpdateRepository : UpdateRepository {
    override fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>> = flowOf(Resource.Error("None"))
    override fun checkForFdroidUpdate(): Flow<Resource<UpdateData>> = flowOf(Resource.Error("None"))
    override fun getFdroidSigningKeys(): Flow<Resource<List<String>>> = flowOf(Resource.Success(emptyList()))
}

class FakeLyricsCanvasRepository : LyricsCanvasRepository {
    override fun getSavedLyrics(videoId: String): Flow<LyricsEntity?> = flowOf(null)
    override suspend fun insertLyrics(lyricsEntity: LyricsEntity) {}
    override suspend fun insertTranslatedLyrics(translatedLyrics: TranslatedLyricsEntity) {}
    override fun getSavedTranslatedLyrics(videoId: String, language: String): Flow<TranslatedLyricsEntity?> = flowOf(null)
    override suspend fun removeTranslatedLyrics(videoId: String, language: String) {}
    override fun getYouTubeCaption(preferLang: String, videoId: String): Flow<Resource<Pair<Lyrics, Lyrics?>>> = flowOf(Resource.Error("None"))
    override fun getCanvas(dataStoreManager: DataStoreManager, videoId: String, duration: Int): Flow<Resource<CanvasResult>> = flowOf(Resource.Error("None"))
    override fun getAMAnimatedArtwork(videoId: String): Flow<Resource<CanvasResult>> = flowOf(Resource.Error("None"))
    override suspend fun updateCanvasUrl(videoId: String, canvasUrl: String) {}
    override suspend fun updateCanvasThumbUrl(videoId: String, canvasThumbUrl: String) {}
    override fun getSpotifyLyrics(dataStoreManager: DataStoreManager, query: String, duration: Int?): Flow<Resource<Lyrics>> = flowOf(Resource.Error("None"))
    override fun getLrclibLyricsData(sartist: String, strack: String, duration: Int?): Flow<Resource<Lyrics>> = flow {
        try {
            val dur = duration ?: 0
            val url = "/api/yt/lyrics?track=" + urlEncode(strack) + "&artist=" + urlEncode(sartist) + "&duration=" + dur
            val resp = webKtorClient.get(url)
            val jsonText = resp.bodyAsText()
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<Lyrics>(jsonText)
            if (!parsed.error && !parsed.lines.isNullOrEmpty()) {
                emit(Resource.Success(parsed))
            } else {
                emit(Resource.Error("Lyrics not found"))
            }
        } catch (e: Throwable) {
            emit(Resource.Error(e.message ?: "Failed to load lyrics"))
        }
    }
    override fun getBetterLyrics(artist: String, track: String, duration: Int?): Flow<Resource<Lyrics>> = getLrclibLyricsData(artist, track, duration)
    override fun getArtistEditorial(artistName: String): Flow<Resource<ArtistEditorial>> = flowOf(Resource.Error("None"))
    override fun getAITranslationLyrics(lyrics: Lyrics, targetLanguage: String): Flow<Resource<Lyrics>> = flowOf(Resource.Error("None"))
    override fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>> = flowOf(Resource.Error("Fallback to LRCLIB"))
    override fun getSimpMusicTranslatedLyrics(videoId: String, language: String): Flow<Resource<Lyrics>> = flowOf(Resource.Error("None"))
    override fun voteSimpMusicLyrics(lyricsId: String, upvote: Boolean): Flow<Resource<String>> = flowOf(Resource.Success("OK"))
    override fun voteSimpMusicTranslatedLyrics(translatedLyricsId: String, upvote: Boolean): Flow<Resource<String>> = flowOf(Resource.Success("OK"))
    override fun insertSimpMusicLyrics(dataStoreManager: DataStoreManager, track: Track, duration: Int, lyrics: Lyrics): Flow<Resource<String>> = flowOf(Resource.Success("OK"))
    override fun insertSimpMusicTranslatedLyrics(dataStoreManager: DataStoreManager, track: Track, translatedLyrics: Lyrics, language: String): Flow<Resource<String>> = flowOf(Resource.Success("OK"))
}

class FakePlaylistRepository : PlaylistRepository {
    override fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>> = flowOf(emptyList())
    override fun getPlaylist(id: String): Flow<PlaylistEntity?> = flowOf(null)
    override fun getLikedPlaylists(): Flow<List<PlaylistEntity>> = flowOf(emptyList())
    override suspend fun insertPlaylist(playlistEntity: PlaylistEntity) {}
    override suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity) {}
    override suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity) {}
    override suspend fun updatePlaylistLiked(playlistId: String, likeStatus: Int) {}
    override suspend fun updatePlaylistInLibrary(inLibrary: LocalDateTime, playlistId: String) {}
    override suspend fun updatePlaylistDownloadState(playlistId: String, downloadState: Int) {}
    override fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>> = flowOf(emptyList())
    override fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>> = flowOf(emptyList())
    override fun getRadio(radioId: String, defaultDescription: String, radioString: String, viewString: String, originalTrack: SongEntity?, artist: ArtistEntity?): Flow<Resource<Pair<PlaylistBrowse, String?>>> = flowOf(Resource.Error("None"))
    override fun getRDATRadioData(radioId: String, viewString: String): Flow<Resource<Pair<PlaylistBrowse, String?>>> = flowOf(Resource.Error("None"))
    override fun getFullPlaylistData(playlistId: String, viewString: String): Flow<Resource<PlaylistBrowse>> = flow {
        runCatching {
            val targetId = if (playlistId.startsWith("VL") || playlistId.startsWith("PL") || playlistId.startsWith("RD")) playlistId else "VL$playlistId"
            val resText = webKtorClient.get("/api/yt/browse?id=" + targetId).bodyAsText()
            val root = Json.parseToJsonElement(resText).jsonObject
            val title = root["title"]?.jsonPrimitive?.content ?: "Playlist"
            val desc = root["description"]?.jsonPrimitive?.content
            val subtitle = root["subtitle"]?.jsonPrimitive?.content ?: ""
            val thumbs = root["thumbnails"]?.jsonArray?.map { thumbElem ->
                val thumbObj = thumbElem.jsonObject
                Thumbnail(
                    height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                    url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                    width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                )
            } ?: emptyList<Thumbnail>()
            val tracksArr = root["tracks"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            val trackList = ArrayList<Track>()
            for (tElem in tracksArr) {
                val tObj = tElem.jsonObject
                val videoId = tObj["videoId"]?.jsonPrimitive?.content ?: continue
                val trackTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val artists = tObj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val album = tObj["album"]?.let { albElem ->
                    if (albElem is kotlinx.serialization.json.JsonObject) {
                        Album(
                            id = albElem["id"]?.jsonPrimitive?.content ?: "",
                            name = albElem["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } else null
                }
                val duration = tObj["duration"]?.jsonPrimitive?.content ?: ""
                val itemThumbs = tObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                trackList.add(
                    Track(
                        album = album,
                        artists = artists,
                        category = "Song",
                        duration = duration,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        feedbackTokens = null,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        resultType = "Song",
                        thumbnails = itemThumbs,
                        title = trackTitle,
                        videoId = videoId,
                        videoType = null,
                        views = null,
                        year = null
                    )
                )
            }
            val playlistBrowse = PlaylistBrowse(
                author = com.maxrave.domain.data.model.browse.playlist.Author(id = "", name = subtitle),
                description = desc,
                duration = "",
                durationSeconds = 0,
                id = playlistId,
                privacy = "PUBLIC",
                thumbnails = thumbs,
                title = title,
                trackCount = trackList.size,
                tracks = trackList,
                year = ""
            )
            emit(Resource.Success<PlaylistBrowse>(playlistBrowse))
        }.onFailure { err ->
            emit(Resource.Error<PlaylistBrowse>(err.message ?: "Failed to load playlist"))
        }
    }
    override fun getPlaylistData(playlistId: String, viewString: String): Flow<Resource<Pair<PlaylistBrowse, String?>>> = flow {
        getFullPlaylistData(playlistId, viewString).collect { res ->
            when (res) {
                is Resource.Success -> emit(Resource.Success(Pair(res.data!!, null)))
                is Resource.Error -> emit(Resource.Error(res.message ?: "Error"))
            }
        }
    }
    override fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?> = flowOf(emptyList())
    override fun getMixedForYou(): Flow<List<PlaylistsResult>?> = flowOf(emptyList())
    override fun updateYourYouTubePlaylistTitle(playlistId: String, newTitle: String): Flow<Resource<String>> = flowOf(Resource.Success("OK"))
    override suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) {}
    override fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?> = flowOf(null)
    override suspend fun deleteAllYourYouTubePlaylist() {}
    override fun getChartPlaylist(): Flow<Resource<List<ChartItem>>> = flowOf(Resource.Success(emptyList()))
}

class FakeLocalPlaylistRepository(
    private val songRepository: SongRepository,
) : LocalPlaylistRepository {
    private val playlists = mutableListOf<LocalPlaylistEntity>()
    private val _playlistsFlow = MutableStateFlow<List<LocalPlaylistEntity>>(emptyList())

    init {
        loadFromStorage()
    }

    private fun loadFromStorage() {
        val json = getLocalStorage("streamify_local_playlists")
        if (!json.isNullOrEmpty()) {
            try {
                val stored = Json { ignoreUnknownKeys = true }.decodeFromString<List<StoredWebPlaylist>>(json)
                playlists.clear()
                for (p in stored) {
                    playlists.add(p.toEntity())
                }
                _playlistsFlow.value = playlists.toList()
            } catch (_: Throwable) {}
        }
    }

    private fun saveToStorage() {
        try {
            val list = playlists.map { p ->
                StoredWebPlaylist(
                    id = p.id,
                    title = p.title,
                    thumbnail = p.thumbnail,
                    tracks = p.tracks ?: emptyList(),
                )
            }
            val json = Json.encodeToString(list)
            setLocalStorage("streamify_local_playlists", json)
            _playlistsFlow.value = playlists.toList()
        } catch (_: Throwable) {}
    }

    override fun getLocalPlaylist(id: Long): Flow<LocalResource<LocalPlaylistEntity?>> = flow {
        emit(LocalResource.Success(playlists.find { it.id == id }))
    }
    override fun getAllLocalPlaylists(): Flow<List<LocalPlaylistEntity>> = _playlistsFlow

    override suspend fun updateLocalPlaylistTracks(tracks: List<String>, id: Long) {
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx != -1) {
            playlists[idx] = playlists[idx].copy(tracks = tracks)
            saveToStorage()
        }
    }
    override suspend fun updateLocalPlaylistDownloadState(downloadState: Int, id: Long) {}
    override suspend fun updateLocalPlaylistYouTubePlaylistSyncState(id: Long, syncState: Int) {}
    override suspend fun insertPairSongLocalPlaylist(pairSongLocalPlaylist: PairSongLocalPlaylist) {}
    override fun getPlaylistPairSongByListPosition(playlistId: Long, listPosition: List<Int>): Flow<List<PairSongLocalPlaylist>?> = flowOf(emptyList())
    override fun getPlaylistPairSongByOffset(playlistId: Long, offset: Int, filterState: FilterState): Flow<List<PairSongLocalPlaylist>?> = flowOf(emptyList())
    override fun getPlaylistPairSongByTime(playlistId: Long, filterState: FilterState, localDateTime: LocalDateTime): Flow<List<PairSongLocalPlaylist>?> = flowOf(emptyList())
    override fun getPlaylistPairOfSong(playlistId: Long, videoId: String): Flow<PairSongLocalPlaylist?> = flowOf(null)
    override fun changePositionOfSongInPlaylist(playlistId: Long, videoId: String, newPosition: Int): Flow<String> = flowOf("OK")
    override fun moveItemInSyncedPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun moveItemInLocalPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun downloadStateFlow(id: Long): Flow<Int> = flowOf(0)
    override fun getAllDownloadingLocalPlaylists(): Flow<List<LocalPlaylistEntity>> = flowOf(emptyList())
    override fun listTrackFlow(id: Long): Flow<List<String>> = flow {
        emit(playlists.find { it.id == id }?.tracks ?: emptyList())
    }
    override fun searchTracks(id: Long, query: String, limit: Int): Flow<List<Pair<SongEntity, PairSongLocalPlaylist>>> = flowOf(emptyList())
    override fun getTracksPaging(id: Long, filter: FilterState): Flow<PagingData<Pair<SongEntity, PairSongLocalPlaylist>>> = flowOf(PagingData.empty())

    override suspend fun getFullPlaylistTracks(id: Long): List<SongEntity> {
        val trackIds = playlists.find { it.id == id }?.tracks ?: return emptyList()
        val result = mutableListOf<SongEntity>()
        for (vid in trackIds) {
            songRepository.getSongById(vid).collect { s ->
                if (s != null) result.add(s)
            }
        }
        return result
    }
    override suspend fun getListTrackVideoId(id: Long): List<String> = playlists.find { it.id == id }?.tracks ?: emptyList()

    override fun insertLocalPlaylist(localPlaylist: LocalPlaylistEntity, successMessage: String): Flow<LocalResource<String>> = flow {
        val newId = if (localPlaylist.id != 0L) localPlaylist.id else (playlists.maxOfOrNull { it.id } ?: 0L) + 1L
        val item = localPlaylist.copy(id = newId)
        val idx = playlists.indexOfFirst { it.id == newId }
        if (idx != -1) {
            playlists[idx] = item
        } else {
            playlists.add(item)
        }
        saveToStorage()
        emit(LocalResource.Success(successMessage))
    }
    override fun deleteLocalPlaylist(id: Long, successMessage: String): Flow<LocalResource<String>> = flow {
        playlists.removeAll { it.id == id }
        saveToStorage()
        emit(LocalResource.Success(successMessage))
    }
    override fun updateTitleLocalPlaylist(id: Long, newTitle: String, updatedMessage: String, updatedYtMessage: String, errorMessage: String): Flow<LocalResource<String>> = flow {
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx != -1) {
            playlists[idx] = playlists[idx].copy(title = newTitle)
            saveToStorage()
            emit(LocalResource.Success(updatedMessage))
        } else {
            emit(LocalResource.Error(errorMessage))
        }
    }
    override fun updateThumbnailLocalPlaylist(id: Long, newThumbnail: String, successMessage: String): Flow<LocalResource<String>> = flow {
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx != -1) {
            playlists[idx] = playlists[idx].copy(thumbnail = newThumbnail)
            saveToStorage()
            emit(LocalResource.Success(successMessage))
        } else {
            emit(LocalResource.Error("Playlist not found"))
        }
    }
    override fun updateDownloadState(id: Long, downloadState: Int, successMessage: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success(successMessage))
    override fun syncYouTubePlaylistToLocalPlaylist(playlist: PlaylistState, tracks: List<Track>, successMessage: String, errorMessage: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun syncLocalPlaylistToYouTubePlaylist(playlistId: Long, successMessage: String, errorMessage: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun unsyncLocalPlaylist(id: Long, successMessage: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun updateSyncState(id: Long, syncState: Int, successMessage: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun updateYouTubePlaylistId(id: Long, youtubePlaylistId: String, successMessage: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
    override fun updateListTrackSynced(id: Long): Flow<Boolean> = flowOf(true)

    override fun addTrackToLocalPlaylist(id: Long, song: SongEntity, successMessage: String, updatedYtMessage: String, errorMessage: String): Flow<LocalResource<String>> = flow {
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx != -1) {
            val curTracks = playlists[idx].tracks?.toMutableList() ?: mutableListOf()
            if (!curTracks.contains(song.videoId)) {
                curTracks.add(song.videoId)
                val thumb = playlists[idx].thumbnail ?: song.thumbnails
                playlists[idx] = playlists[idx].copy(tracks = curTracks, thumbnail = thumb)
                saveToStorage()
            }
            songRepository.insertSong(song)
            emit(LocalResource.Success(successMessage))
        } else {
            emit(LocalResource.Error(errorMessage))
        }
    }
    override fun removeTrackFromLocalPlaylist(id: Long, song: SongEntity, successMessage: String, updatedYtMessage: String, errorMessage: String): Flow<LocalResource<String>> = flow {
        val idx = playlists.indexOfFirst { it.id == id }
        if (idx != -1) {
            val curTracks = playlists[idx].tracks?.toMutableList() ?: mutableListOf()
            curTracks.remove(song.videoId)
            playlists[idx] = playlists[idx].copy(tracks = curTracks)
            saveToStorage()
            emit(LocalResource.Success(successMessage))
        } else {
            emit(LocalResource.Error(errorMessage))
        }
    }
    override fun getSuggestionsTrackForPlaylist(id: Long): Flow<LocalResource<Pair<String?, List<Track>>>> = flowOf(LocalResource.Error("None"))
    override fun reloadSuggestionPlaylist(reloadParams: String): Flow<LocalResource<Pair<String?, List<Track>>>> = flowOf(LocalResource.Error("None"))
    override fun getYouTubeSetVideoId(youtubePlaylistId: String): Flow<List<SetVideoIdEntity>> = flowOf(emptyList())
    override fun addYouTubePlaylistItem(youtubePlaylistId: String, videoId: String): Flow<LocalResource<String>> = flowOf(LocalResource.Success("OK"))
}

class FakeSongRepository : SongRepository {
    private val songsMap = mutableMapOf<String, SongEntity>()
    private val _songsFlow = MutableStateFlow<List<SongEntity>>(emptyList())

    init {
        loadFromStorage()
    }

    private fun loadFromStorage() {
        val json = getLocalStorage("streamify_songs")
        if (!json.isNullOrEmpty()) {
            try {
                val stored = Json { ignoreUnknownKeys = true }.decodeFromString<List<StoredWebSong>>(json)
                for (s in stored) {
                    songsMap[s.videoId] = s.toEntity()
                }
                _songsFlow.value = songsMap.values.toList()
            } catch (_: Throwable) {}
        }
    }

    private fun saveToStorage() {
        try {
            val list = songsMap.values.map { s ->
                StoredWebSong(
                    videoId = s.videoId,
                    title = s.title,
                    artistName = s.artistName?.firstOrNull() ?: "",
                    albumName = s.albumName ?: "",
                    duration = s.duration,
                    durationSeconds = s.durationSeconds,
                    thumbnails = s.thumbnails ?: "",
                    liked = s.liked,
                    totalPlayTime = s.totalPlayTime,
                )
            }
            val json = Json.encodeToString(list)
            setLocalStorage("streamify_songs", json)
            _songsFlow.value = songsMap.values.toList()
        } catch (_: Throwable) {}
    }

    override fun getAllSongs(limit: Int): Flow<List<SongEntity>> = _songsFlow.map { it.take(limit) }
    override suspend fun setInLibrary(videoId: String, inLibrary: LocalDateTime) {}
    override fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>> = _songsFlow.map { list ->
        listVideoId.mapNotNull { id -> list.find { it.videoId == id } }
    }
    override fun getDownloadedSongs(): Flow<List<SongEntity>?> = flowOf(emptyList())
    override fun getDownloadingSongs(): Flow<List<SongEntity>?> = flowOf(emptyList())
    override fun getPreparingSongs(): Flow<List<SongEntity>> = flowOf(emptyList())
    override fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>): Flow<List<String>> = flowOf(emptyList())

    override fun getLikedSongs(): Flow<List<SongEntity>> = _songsFlow.map { list ->
        list.filter { it.liked }
    }
    override fun getLikedSongsByArtist(channelId: String): Flow<List<SongEntity>> = _songsFlow.map { list ->
        list.filter { it.liked && (it.artistId?.contains(channelId) == true) }
    }
    override suspend fun downloadAllLikedSongs(): Int = 0
    override suspend fun runSmartDownloads(count: Int): Int = 0
    override suspend fun clearSmartDownloads() {}
    override suspend fun clearHistoryAndOrphanedSongs(): Int = 0
    override fun getCanvasSong(max: Int): Flow<List<SongEntity>> = flowOf(emptyList())

    override fun getSongById(id: String): Flow<SongEntity?> = flow {
        emit(songsMap[id])
    }
    override fun getSongAsFlow(id: String): Flow<SongEntity?> = _songsFlow.map { list ->
        list.find { it.videoId == id }
    }
    override fun insertSong(songEntity: SongEntity): Flow<Long> = flow {
        val existing = songsMap[songEntity.videoId]
        if (existing != null) {
            songsMap[songEntity.videoId] = existing.copy(
                title = songEntity.title,
                thumbnails = songEntity.thumbnails ?: existing.thumbnails,
                duration = songEntity.duration,
                durationSeconds = songEntity.durationSeconds,
                artistName = songEntity.artistName ?: existing.artistName,
                albumName = songEntity.albumName ?: existing.albumName,
                liked = songEntity.liked || existing.liked,
                likeStatus = if (songEntity.liked || existing.liked) "LIKE" else existing.likeStatus,
                favoriteAt = existing.favoriteAt ?: (if (songEntity.liked) now() else null)
            )
        } else {
            songsMap[songEntity.videoId] = songEntity
        }
        saveToStorage()
        emit(1L)
    }
    override fun updateThumbnailsSongEntity(thumbnail: String, videoId: String): Flow<Int> = flow {
        songsMap[videoId]?.let {
            songsMap[videoId] = it.copy(thumbnails = thumbnail)
            saveToStorage()
        }
        emit(1)
    }
    override fun updateVideoTypeSongEntity(videoType: String, videoId: String): Flow<Int> = flowOf(1)

    override suspend fun updateListenCount(videoId: String) {
        val existing = songsMap[videoId]
        if (existing != null) {
            val playTime = existing.totalPlayTime + existing.durationSeconds.coerceAtLeast(180)
            songsMap[videoId] = existing.copy(totalPlayTime = playTime)
            saveToStorage()
        }
    }
    override suspend fun resetTotalPlayTime(videoId: String) {
        songsMap[videoId]?.let {
            songsMap[videoId] = it.copy(totalPlayTime = 0)
            saveToStorage()
        }
    }
    override suspend fun updateLikeStatus(videoId: String, likeStatus: Int) {
        val isLiked = likeStatus == 1
        val existing = songsMap[videoId]
        if (existing != null) {
            songsMap[videoId] = existing.copy(
                liked = isLiked,
                likeStatus = if (isLiked) "LIKE" else "INDIFFERENT",
                favoriteAt = if (isLiked) now() else null
            )
        } else {
            songsMap[videoId] = SongEntity(
                videoId = videoId,
                title = "Track $videoId",
                duration = "",
                durationSeconds = 0,
                isAvailable = true,
                isExplicit = false,
                likeStatus = if (isLiked) "LIKE" else "INDIFFERENT",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                category = "Song",
                resultType = "Song",
                liked = isLiked,
                favoriteAt = if (isLiked) now() else null
            )
        }
        saveToStorage()
    }
    override fun updateSongInLibrary(inLibrary: LocalDateTime, videoId: String): Flow<Int> = flowOf(1)
    override suspend fun updateDurationSeconds(durationSeconds: Int, videoId: String) {
        songsMap[videoId]?.let {
            songsMap[videoId] = it.copy(durationSeconds = durationSeconds)
            saveToStorage()
        }
    }
    override fun getMostPlayedSongs(): Flow<List<SongEntity>> = _songsFlow.map { list ->
        list.filter { it.totalPlayTime > 0 }.sortedByDescending { it.totalPlayTime }
    }
    override suspend fun updateDownloadState(videoId: String, downloadState: Int) {}
    override suspend fun insertSongInfo(songInfo: SongInfoEntity) {}
    override suspend fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?> = flowOf(null)
    override suspend fun recoverQueue(temp: List<Track>) {}
    override suspend fun removeQueue() {}
    override suspend fun getSavedQueue(): Flow<List<QueueEntity>?> = flowOf(emptyList())
    override fun getContinueTrack(playlistId: String, continuation: String, fromPlaylist: Boolean): Flow<Pair<ArrayList<Track>?, String?>> = flow {
        runCatching {
            val query = "/api/yt/next?playlistId=" + urlEncode(playlistId) + "&continuation=" + urlEncode(continuation)
            val resText = webKtorClient.get(query).bodyAsText()
            val root = Json.parseToJsonElement(resText).jsonObject
            val nextCont = root["continuation"]?.jsonPrimitive?.content
            val tracksArr = root["tracks"]?.jsonArray ?: emptyList()
            val trackList = ArrayList<Track>()
            for (tElem in tracksArr) {
                val tObj = tElem.jsonObject
                val videoId = tObj["videoId"]?.jsonPrimitive?.content ?: continue
                val trackTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val artists = tObj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val album = tObj["album"]?.let { albElem ->
                    if (albElem is JsonObject) {
                        Album(
                            id = albElem["id"]?.jsonPrimitive?.content ?: "",
                            name = albElem["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } else null
                }
                val duration = tObj["duration"]?.jsonPrimitive?.content ?: ""
                val itemThumbs = tObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                trackList.add(
                    Track(
                        album = album,
                        artists = artists,
                        category = "Song",
                        duration = duration,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        feedbackTokens = null,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        resultType = "Song",
                        thumbnails = itemThumbs,
                        title = trackTitle,
                        videoId = videoId,
                        videoType = null,
                        views = null,
                        year = tObj["year"]?.jsonPrimitive?.content
                    )
                )
            }
            emit(Pair(trackList, nextCont))
        }.onFailure {
            emit(Pair(ArrayList(), null))
        }
    }
    override fun getSongInfo(videoId: String): Flow<SongInfoEntity?> = flowOf(null)

    override suspend fun getLikeStatus(videoId: String): Flow<YouTubeLikeStatus> = flow {
        val s = songsMap[videoId]
        if (s?.liked == true) emit(YouTubeLikeStatus.LIKE) else emit(YouTubeLikeStatus.INDIFFERENT)
    }
    override suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int> = flow {
        if (mediaId != null) updateLikeStatus(mediaId, 1)
        emit(200)
    }
    override suspend fun addToYouTubeDisliked(mediaId: String?): Flow<Int> = flow {
        if (mediaId != null) updateLikeStatus(mediaId, 0)
        emit(200)
    }
    override suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int> = flow {
        if (mediaId != null) updateLikeStatus(mediaId, 0)
        emit(200)
    }
    override fun downloadToFile(track: Track, path: String, videoId: String, isVideo: Boolean): Flow<DownloadProgress> = flowOf(DownloadProgress.INIT)
    override fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>> = flow {
        runCatching {
            val resText = webKtorClient.get("/api/yt/next?videoId=" + urlEncode(videoId)).bodyAsText()
            val root = Json.parseToJsonElement(resText).jsonObject
            val continuation = root["continuation"]?.jsonPrimitive?.content
            val tracksArr = root["tracks"]?.jsonArray ?: emptyList()
            val trackList = ArrayList<Track>()
            for (tElem in tracksArr) {
                val tObj = tElem.jsonObject
                val vId = tObj["videoId"]?.jsonPrimitive?.content ?: continue
                val trackTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val artists = tObj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val album = tObj["album"]?.let { albElem ->
                    if (albElem is JsonObject) {
                        Album(
                            id = albElem["id"]?.jsonPrimitive?.content ?: "",
                            name = albElem["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } else null
                }
                val duration = tObj["duration"]?.jsonPrimitive?.content ?: ""
                val itemThumbs = tObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                trackList.add(
                    Track(
                        album = album,
                        artists = artists,
                        category = "Song",
                        duration = duration,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        feedbackTokens = null,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        resultType = "Song",
                        thumbnails = itemThumbs,
                        title = trackTitle,
                        videoId = vId,
                        videoType = null,
                        views = null,
                        year = tObj["year"]?.jsonPrimitive?.content
                    )
                )
            }
            emit(Resource.Success<Pair<List<Track>, String?>>(Pair(trackList, continuation)))
        }.onFailure {
            emit(Resource.Error<Pair<List<Track>, String?>>(it.message ?: "Failed to get related tracks"))
        }
    }
    override fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>> = flowOf(Resource.Error("None"))
    override fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>> = flowOf(Resource.Error("None"))
    override fun postComment(createCommentParams: String, text: String): Flow<Resource<YouTubeComment?>> = flowOf(Resource.Error("None"))
    override fun postCommentReply(createReplyParams: String, text: String): Flow<Resource<YouTubeComment?>> = flowOf(Resource.Error("None"))
    override fun performCommentAction(action: String): Flow<Resource<Unit>> = flowOf(Resource.Error("None"))
    override fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>> = flow {
        runCatching {
            val vId = endpoint.videoId
            val pId = endpoint.playlistId
            val cont = endpoint.params
            val query = StringBuilder("/api/yt/next?")
            if (!vId.isNullOrEmpty()) query.append("videoId=").append(urlEncode(vId)).append("&")
            if (!pId.isNullOrEmpty()) query.append("playlistId=").append(urlEncode(pId)).append("&")
            if (!cont.isNullOrEmpty()) query.append("continuation=").append(urlEncode(cont))
            val resText = webKtorClient.get(query.toString().trimEnd('&')).bodyAsText()
            val root = Json.parseToJsonElement(resText).jsonObject
            val continuation = root["continuation"]?.jsonPrimitive?.content
            val tracksArr = root["tracks"]?.jsonArray ?: emptyList()
            val trackList = ArrayList<Track>()
            for (tElem in tracksArr) {
                val tObj = tElem.jsonObject
                val trackVid = tObj["videoId"]?.jsonPrimitive?.content ?: continue
                val trackTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val artists = tObj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val album = tObj["album"]?.let { albElem ->
                    if (albElem is JsonObject) {
                        Album(
                            id = albElem["id"]?.jsonPrimitive?.content ?: "",
                            name = albElem["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } else null
                }
                val duration = tObj["duration"]?.jsonPrimitive?.content ?: ""
                val itemThumbs = tObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                trackList.add(
                    Track(
                        album = album,
                        artists = artists,
                        category = "Song",
                        duration = duration,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        feedbackTokens = null,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        resultType = "Song",
                        thumbnails = itemThumbs,
                        title = trackTitle,
                        videoId = trackVid,
                        videoType = null,
                        views = null,
                        year = tObj["year"]?.jsonPrimitive?.content
                    )
                )
            }
            emit(Resource.Success<Pair<List<Track>, String?>>(Pair(trackList, continuation)))
        }.onFailure {
            emit(Resource.Error<Pair<List<Track>, String?>>(it.message ?: "Failed to get radio"))
        }
    }
}

class FakeAlbumRepository : AlbumRepository {
    override fun getAllAlbums(limit: Int): Flow<List<AlbumEntity>> = flowOf(emptyList())
    override fun getAlbum(id: String): Flow<AlbumEntity?> = flowOf(null)
    override fun getAlbumAsFlow(id: String): Flow<AlbumEntity?> = flowOf(null)
    override fun getLikedAlbums(): Flow<List<AlbumEntity>> = flowOf(emptyList())
    override fun insertAlbum(albumEntity: AlbumEntity): Flow<Long> = flowOf(0L)
    override suspend fun updateAlbumLiked(albumId: String, likeStatus: Int) {}
    override suspend fun updateAlbumInLibrary(inLibrary: LocalDateTime, albumId: String) {}
    override suspend fun updateAlbumDownloadState(albumId: String, downloadState: Int) {}
    override suspend fun insertFollowedArtistSingleAndAlbum(followedArtistSingleAndAlbum: FollowedArtistSingleAndAlbum) {}
    override suspend fun deleteFollowedArtistSingleAndAlbum(channelId: String) {}
    override suspend fun getAllFollowedArtistSingleAndAlbums(): Flow<List<FollowedArtistSingleAndAlbum>?> = flowOf(emptyList())
    override suspend fun getFollowedArtistSingleAndAlbum(channelId: String): Flow<FollowedArtistSingleAndAlbum?> = flowOf(null)
    override fun getAlbumData(browseId: String): Flow<Resource<AlbumBrowse>> = flow {
        runCatching {
            val resText = webKtorClient.get("/api/yt/browse?id=" + browseId).bodyAsText()
            val root = Json.parseToJsonElement(resText).jsonObject
            val title = root["title"]?.jsonPrimitive?.content ?: "Album"
            val desc = root["description"]?.jsonPrimitive?.content
            val subtitle = root["subtitle"]?.jsonPrimitive?.content ?: ""
            val thumbs = root["thumbnails"]?.jsonArray?.map { thumbElem ->
                val thumbObj = thumbElem.jsonObject
                Thumbnail(
                    height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                    url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                    width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                )
            } ?: emptyList<Thumbnail>()
            val tracksArr = root["tracks"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            val trackList = ArrayList<Track>()
            for (tElem in tracksArr) {
                val tObj = tElem.jsonObject
                val videoId = tObj["videoId"]?.jsonPrimitive?.content ?: continue
                val trackTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val artists = tObj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val album = tObj["album"]?.let { albElem ->
                    if (albElem is kotlinx.serialization.json.JsonObject) {
                        Album(
                            id = albElem["id"]?.jsonPrimitive?.content ?: "",
                            name = albElem["name"]?.jsonPrimitive?.content ?: ""
                        )
                    } else null
                }
                val duration = tObj["duration"]?.jsonPrimitive?.content ?: ""
                val itemThumbs = tObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                trackList.add(
                    Track(
                        album = album,
                        artists = artists,
                        category = "Song",
                        duration = duration,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        feedbackTokens = null,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        resultType = "Song",
                        thumbnails = itemThumbs,
                        title = trackTitle,
                        videoId = videoId,
                        videoType = null,
                        views = null,
                        year = null
                    )
                )
            }
            val albumBrowse = AlbumBrowse(
                artists = listOf(com.maxrave.domain.data.model.searchResult.songs.Artist(id = "", name = subtitle)),
                audioPlaylistId = browseId,
                description = desc,
                duration = null,
                durationSeconds = 0,
                thumbnails = thumbs,
                title = title,
                trackCount = trackList.size,
                tracks = trackList,
                type = "Album",
                year = null
            )
            emit(Resource.Success<AlbumBrowse>(albumBrowse))
        }.onFailure { err ->
            emit(Resource.Error<AlbumBrowse>(err.message ?: "Failed to load album"))
        }
    }
    override fun getAlbumMore(browseId: String, params: String): Flow<Pair<String, List<AlbumsResult>>?> = flowOf(null)
}

class FakeCacheRepository : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long = 0L
    override fun clearCache(cacheName: String) {}
    override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()
    override suspend fun getFullyCachedSongIds(): List<String> = emptyList()
    override suspend fun removeCachedSong(videoId: String) {}
}

class FakeDiscordRepository : DiscordRepository {
    override val isOAuth: Boolean = false
    override val loggedIn: Flow<Boolean> = flowOf(false)
    override fun startLogin(): String? = null
    override suspend fun completeLogin(callbackUrl: String): Boolean = false
    override suspend fun accessToken(forceRefresh: Boolean): String? = null
    override suspend fun logout() {}
}

class FakeListenTogetherRepository : ListenTogetherRepository {
    override val room: StateFlow<ListenTogetherRoom> = MutableStateFlow(ListenTogetherRoom())
    override var autoApproveJoins: Boolean = true
    override var autoApproveSuggestions: Boolean = true
    override fun connect() {}
    override fun disconnect() {}
    override fun createRoom(username: String) {}
    override fun joinRoom(roomCode: String, username: String) {}
    override fun cancelJoin() {}
    override fun leaveRoom() {}
    override fun approveJoin(userId: String) {}
    override fun rejectJoin(userId: String) {}
    override fun approveSuggestion(suggestionId: String) {}
    override fun rejectSuggestion(suggestionId: String) {}
    override fun kickUser(userId: String) {}
    override fun transferHost(userId: String) {}
    override fun suggestTrack(track: RoomTrack) {}
    override fun requestSync() {}
    override fun clearError() {}
}

class FakeArtistRepository : ArtistRepository {
    override fun getAllArtists(limit: Int): Flow<List<ArtistEntity>> = flowOf(emptyList())
    override fun getArtistById(id: String): Flow<ArtistEntity?> = flowOf(null)
    override suspend fun insertArtist(artistEntity: ArtistEntity) {}
    override suspend fun updateArtistImage(channelId: String, thumbnail: String) {}
    override suspend fun updateArtistNameLogo(channelId: String, nameLogoUrl: String?, nameLogoColor: String?) {}
    override suspend fun getArtistMotion(channelId: String): ArtistMotionEntity? = null
    override suspend fun upsertArtistMotion(motion: ArtistMotionEntity) {}
    override suspend fun updateFollowedStatus(channelId: String, followedStatus: Int): Boolean? = null
    override fun syncFollowedArtistsToYouTube(): Flow<Pair<Int, Int>> = flowOf(Pair(0, 0))
    override fun getFollowedArtists(): Flow<List<ArtistEntity>> = flowOf(emptyList())
    override suspend fun updateArtistInLibrary(inLibrary: LocalDateTime, channelId: String) {}
    override fun getArtistData(channelId: String): Flow<Resource<com.maxrave.domain.data.model.browse.artist.ArtistBrowse>> = flow {
        runCatching {
            val resText = webKtorClient.get("/api/yt/browse?id=" + channelId).bodyAsText()
            val root = Json.parseToJsonElement(resText).jsonObject
            val name = root["name"]?.jsonPrimitive?.content ?: "Artist"
            val subs = root["subscribers"]?.jsonPrimitive?.content
            val thumbs = root["thumbnails"]?.jsonArray?.map { thumbElem ->
                val thumbObj = thumbElem.jsonObject
                Thumbnail(
                    height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                    url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                    width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                )
            } ?: emptyList<Thumbnail>()
            val songsArr = root["songs"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
            val songList = ArrayList<com.maxrave.domain.data.model.browse.artist.ResultSong>()
            for (tElem in songsArr) {
                val tObj = tElem.jsonObject
                val videoId = tObj["videoId"]?.jsonPrimitive?.content ?: continue
                val trackTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val artists = tObj["artists"]?.jsonArray?.map { artElem ->
                    val artObj = artElem.jsonObject
                    com.maxrave.domain.data.model.searchResult.songs.Artist(
                        id = artObj["id"]?.jsonPrimitive?.content,
                        name = artObj["name"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList<com.maxrave.domain.data.model.searchResult.songs.Artist>()
                val duration = tObj["duration"]?.jsonPrimitive?.content ?: ""
                val itemThumbs = tObj["thumbnails"]?.jsonArray?.map { thumbElem ->
                    val thumbObj = thumbElem.jsonObject
                    Thumbnail(
                        height = thumbObj["height"]?.jsonPrimitive?.intOrNull ?: 120,
                        url = sanitizeThumbnailUrl(thumbObj["url"]?.jsonPrimitive?.content ?: ""),
                        width = thumbObj["width"]?.jsonPrimitive?.intOrNull ?: 120
                    )
                } ?: emptyList<Thumbnail>()
                songList.add(
                    com.maxrave.domain.data.model.browse.artist.ResultSong(
                        album = Album(id = "", name = ""),
                        artists = artists,
                        durationSeconds = parseDurationStringToSeconds(duration),
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = "INDIFFERENT",
                        thumbnails = itemThumbs,
                        title = trackTitle,
                        videoId = videoId,
                        videoType = null,
                        isLive = false
                    )
                )
            }
            val artistBrowse = com.maxrave.domain.data.model.browse.artist.ArtistBrowse(
                albums = null,
                channelId = channelId,
                description = null,
                featuredOn = null,
                name = name,
                radioId = null,
                related = null,
                shuffleId = null,
                singles = null,
                songs = if (songList.isNotEmpty()) com.maxrave.domain.data.model.browse.artist.Songs(null, songList) else null,
                subscribed = false,
                subscribers = subs,
                thumbnails = thumbs,
                video = null,
                videoList = null,
                views = null
            )
            emit(Resource.Success<com.maxrave.domain.data.model.browse.artist.ArtistBrowse>(artistBrowse))
        }.onFailure { err ->
            emit(Resource.Error<com.maxrave.domain.data.model.browse.artist.ArtistBrowse>(err.message ?: "Failed to load artist"))
        }
    }
}

class FakeLyricsRomanizerRepository : LyricsRomanizerRepository {
    override fun romanize(line: String, enabled: Set<RomanizationLanguage>): String? = null
    override val japaneseDictionaryState: StateFlow<RomanizationDictionaryState> = MutableStateFlow(RomanizationDictionaryState.READY)
    override suspend fun downloadJapaneseDictionary(): RomanizationDictionaryState = RomanizationDictionaryState.READY
    override fun ensureJapaneseDictionary() {}
}

class FakeCommonRepository(
    private val songRepository: SongRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
) : CommonRepository {
    override fun init(cookiePath: String, dataStoreManager: DataStoreManager, isDevBuild: Boolean) {}
    override fun closeDatabase() {}
    override fun getDatabasePath(): String? = "streamify_web_indexeddb"
    override suspend fun databaseDaoCheckpoint() {}

    override fun getAllRecentData(): Flow<List<RecentlyType>> = songRepository.getAllSongs(20)

    override fun getLibraryOverview(): Flow<LibraryOverview> = combine(
        songRepository.getLikedSongs(),
        songRepository.getMostPlayedSongs()
    ) { liked, mostPlayed ->
        LibraryOverview(
            favorite = LibraryCollectionPreview(
                count = liked.size,
                thumbnails = liked.mapNotNull { it.thumbnails }.take(3),
            ),
            followed = LibraryCollectionPreview(count = 0, thumbnails = emptyList()),
            mostPlayed = LibraryCollectionPreview(
                count = mostPlayed.size,
                thumbnails = mostPlayed.mapNotNull { it.thumbnails }.take(3),
            ),
            downloaded = LibraryCollectionPreview(count = 0, thumbnails = emptyList()),
        )
    }

    override suspend fun insertNotification(notificationEntity: NotificationEntity) {}
    override suspend fun getAllNotifications(): Flow<List<NotificationEntity>?> = flowOf(emptyList())
    override suspend fun isNotificationExists(link: String): Boolean = false
    override suspend fun deleteNotification(id: Long) {}
    override suspend fun writeTextToFile(text: String, filePath: String): Boolean = true
    override suspend fun getCookiesFromInternalDatabase(url: String, packageName: String): CookieItem =
        CookieItem(url = url, content = emptyList())
}

class FakeAnalyticsRepository(
    private val songRepository: SongRepository,
) : AnalyticsRepository {
    override suspend fun insertPlaybackEvent(
        videoId: String,
        channelIds: List<String>,
        albumBrowseId: String?,
        durationSecond: Long,
        listenedSecond: Long,
    ): Flow<Long> = flow {
        songRepository.updateListenCount(videoId)
        emit(1L)
    }

    override suspend fun getPlaybackEventsByOffset(offset: Int, limit: Int): Flow<List<PlaybackEventEntity>> = flowOf(emptyList())
    override suspend fun getPlaybackEventsByOffsetAndTimestamp(offset: Int, limit: Int, cutoffTimestamp: LocalDateTime): Flow<List<PlaybackEventEntity>> = flowOf(emptyList())
    override suspend fun deleteOldPlaybackEvents(cutoffTimestamp: LocalDateTime) {}

    override suspend fun queryTopPlayedSongsLastXDays(x: Int): Flow<List<TopPlayedTracks>> = flow {
        songRepository.getMostPlayedSongs().collect { songs ->
            emit(songs.map { s ->
                TopPlayedTracks(
                    videoId = s.videoId,
                    playCount = (s.totalPlayTime / 60).toInt().coerceAtLeast(1),
                    totalListeningTime = s.totalPlayTime,
                )
            })
        }
    }

    override suspend fun queryTopPlayedSongsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Flow<List<TopPlayedTracks>> =
        queryTopPlayedSongsLastXDays(30)

    override suspend fun queryTopArtistsLastXDays(x: Int): Flow<List<TopPlayedArtist>> = flowOf(emptyList())
    override suspend fun queryTopArtistsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Flow<List<TopPlayedArtist>> = flowOf(emptyList())
    override suspend fun queryTopArtistsWithTimeInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Flow<List<TopPlayedArtistTime>> = flowOf(emptyList())
    override suspend fun queryTopAlbumsLastXDays(x: Int): Flow<List<TopPlayedAlbum>> = flowOf(emptyList())
    override suspend fun queryTopAlbumsInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Flow<List<TopPlayedAlbum>> = flowOf(emptyList())

    override suspend fun getTotalPlaybackEventCount(): Flow<Long> = flow {
        songRepository.getMostPlayedSongs().collect { songs ->
            emit(songs.size.toLong())
        }
    }
    override suspend fun getTotalEventArtistCount(): Flow<Long> = flowOf(0L)
    override suspend fun getTotalListeningTimeInSeconds(): Flow<Long> = flow {
        songRepository.getMostPlayedSongs().collect { songs ->
            emit(songs.sumOf { it.totalPlayTime })
        }
    }
    override suspend fun getPlaybackEventCountInRange(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): Flow<Long> = getTotalPlaybackEventCount()

    override suspend fun getPeriodStats(startTimestamp: LocalDateTime, endTimestamp: LocalDateTime): AnalyticsPeriodStats {
        var count = 0L
        var totalSec = 0L
        songRepository.getMostPlayedSongs().collect { songs ->
            count = songs.size.toLong()
            totalSec = songs.sumOf { it.totalPlayTime }
        }
        return AnalyticsPeriodStats(
            plays = count,
            listenedSeconds = totalSec,
            distinctTracks = count.toInt(),
        )
    }
}

class WebDownloadHandler : DownloadHandler {
    private val _downloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())
    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>> = _downloads

    private val _downloadTask = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloadTask: StateFlow<Map<String, Int>> = _downloadTask

    override suspend fun downloadTrack(videoId: String, title: String, thumbnail: String) {}
    override fun removeDownload(videoId: String) {}
    override fun removeAllDownloads() {}
}

class FakeAccountRepository : AccountRepository {
    override fun getAccountInfo(cookie: String): Flow<List<AccountInfo>> = flowOf(emptyList())
    override fun getYouTubeCookie(): String? = null
    override fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity): Flow<Long> = flowOf(1L)
    override fun getGoogleAccounts(): Flow<List<GoogleAccountEntity>?> = flowOf(emptyList())
    override fun getUsedGoogleAccount(): Flow<GoogleAccountEntity?> = flowOf(null)
    override suspend fun deleteGoogleAccount(email: String) {}
    override fun updateGoogleAccountUsed(email: String, isUsed: Boolean): Flow<Int> = flowOf(1)
}

class FakePodcastRepository : PodcastRepository {
    override fun getPodcastData(podcastId: String): Flow<Resource<PodcastBrowse>> = flowOf(Resource.Error("None"))
    override fun insertPodcast(podcastsEntity: PodcastsEntity): Flow<Long> = flowOf(1L)
    override fun insertEpisodes(episodes: List<EpisodeEntity>): Flow<List<Long>> = flowOf(emptyList())
    override fun getPodcastWithEpisodes(podcastId: String): Flow<PodcastWithEpisodes?> = flowOf(null)
    override fun getAllPodcasts(limit: Int): Flow<List<PodcastsEntity>> = flowOf(emptyList())
    override fun getAllPodcastWithEpisodes(): Flow<List<PodcastWithEpisodes>> = flowOf(emptyList())
    override fun getPodcast(podcastId: String): Flow<PodcastsEntity?> = flowOf(null)
    override fun getEpisode(videoId: String): Flow<EpisodeEntity?> = flowOf(null)
    override fun deletePodcast(podcastId: String): Flow<Int> = flowOf(1)
    override fun favoritePodcast(podcastId: String, favorite: Boolean): Flow<Boolean> = flowOf(true)
    override fun getPodcastEpisodes(podcastId: String): Flow<List<EpisodeEntity>> = flowOf(emptyList())
    override fun getFavoritePodcasts(): Flow<List<PodcastsEntity>> = flowOf(emptyList())
    override fun updatePodcastInLibraryNow(id: String): Flow<Int> = flowOf(1)
}

class FakeAutoEqRepository : AutoEqRepository {
    override suspend fun search(query: String, limit: Int): List<AutoEqEntryEntity> = emptyList()
    override suspend fun cachedCount(): Int = 0
    override suspend fun refreshIndex(force: Boolean): Boolean = false
    override suspend fun cachedCurvePaths(): Set<String> = emptySet()
    override suspend fun loadCurve(entry: AutoEqEntryEntity): AutoEqCurve? = null
}

class FakeLibrarySyncRepository : LibrarySyncRepository {
    override val state: StateFlow<LibrarySyncState> = MutableStateFlow(LibrarySyncState())
    override val pendingSettings: StateFlow<PendingSettings?> = MutableStateFlow(null)
    override suspend fun syncNow() {}
    override suspend fun unpairAll() {}
    override suspend fun applyPendingSettings() {}
    override suspend fun dismissPendingSettings() {}
}

class FakeBrowserLoginRepository : BrowserLoginRepository {
    override suspend fun profiles(target: BrowserLoginTarget): List<BrowserProfile> = emptyList()
    override suspend fun cookies(profile: BrowserProfile, target: BrowserLoginTarget): BrowserCookies =
        BrowserCookies("", "")
}

class FakeImportRepository : ImportRepository {
    override fun import(json: String, invalidFileMessage: String): Flow<ImportProgress> = flowOf(
        ImportProgress.Error("Import is not supported on web")
    )
}

class FakeTasteRepository : TasteRepository {
    override val tasteProfile: Flow<TasteProfile?> = flowOf(null)
    override suspend fun describeTaste(input: TasteInput, artistImages: List<String>): Result<TasteProfile> =
        Result.failure(Exception("AI Taste Profile is not supported on web"))
}

class FakeLoginSyncHostRepository : LoginSyncHostRepository {
    override val state: StateFlow<LoginSyncHostState> = MutableStateFlow(LoginSyncHostState.Starting)
    override suspend fun start() {}
    override fun select(address: NetAddress) {}
    override suspend fun expire() {}
    override fun stop() {}
}

class FakeLoginSyncSenderRepository : LoginSyncSenderRepository {
    override suspend fun signedInServices(): List<LoginSyncService> = emptyList()
    override fun isInvite(qrText: String): Boolean = false
    override suspend fun connect(qrText: String): DesktopInfo =
        throw Exception("Login sync sender is not supported on web")
    override suspend fun send(services: Set<LoginSyncService>): List<LoginSyncService> = emptyList()
    override fun disconnect() {}
}

class FakeLibrarySyncHostRepository : LibrarySyncHostRepository {
    override val state: StateFlow<LibrarySyncState> = MutableStateFlow(LibrarySyncState())
    override val pendingSettings: StateFlow<PendingSettings?> = MutableStateFlow(null)
    override suspend fun syncNow() {}
    override suspend fun unpairAll() {}
    override suspend fun applyPendingSettings() {}
    override suspend fun dismissPendingSettings() {}
    override val pairing: StateFlow<LibrarySyncPairingState> = MutableStateFlow(LibrarySyncPairingState.Idle)
    override val pendingLogins: StateFlow<ReceivedLogins?> = MutableStateFlow(null)
    override fun answerLogins(accept: Boolean) {}
    override suspend fun start() {}
    override suspend fun startPairing() {}
    override fun selectPairingAddress(address: NetAddress) {}
    override suspend fun stopPairing() {}
    override suspend fun removePhone(phoneId: String) {}
    override suspend fun checkNetworkProfile() {}
}

class FakeLibrarySyncPhoneRepository : LibrarySyncPhoneRepository {
    override val state: StateFlow<LibrarySyncState> = MutableStateFlow(LibrarySyncState())
    override val pendingSettings: StateFlow<PendingSettings?> = MutableStateFlow(null)
    override suspend fun syncNow() {}
    override suspend fun unpairAll() {}
    override suspend fun applyPendingSettings() {}
    override suspend fun dismissPendingSettings() {}
    override fun isPairingCode(qrText: String): Boolean = false
    override suspend fun reach(qrText: String): DesktopInfo =
        throw Exception("Library sync is not supported on web")
    override suspend fun pair(): SyncPeer =
        throw Exception("Library sync is not supported on web")
    override fun cancelPairing() {}
    override suspend fun sendLogins(services: Set<LoginSyncService>): List<LoginSyncService> = emptyList()
}
