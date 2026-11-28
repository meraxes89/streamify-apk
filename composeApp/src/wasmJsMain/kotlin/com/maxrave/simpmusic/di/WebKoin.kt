package com.maxrave.simpmusic.di

import com.eygraber.uri.Uri
import com.maxrave.common.AppIdentity
import com.maxrave.common.Config
import com.maxrave.domain.data.entities.*
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistEditorial
import com.maxrave.domain.data.model.canvas.CanvasResult
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.*
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.player.WebMediaPlayerHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.dsl.module

val webKtorClient = HttpClient(Js)

val webAppModule = module {
    single<DataStoreManager> {
        FakeDataStoreManager()
    }

    single {
        AppIdentity(
            applicationId = "com.maxrave.simpmusic",
            versionName = "2.6.0-web",
            platform = "Web",
            isDevBuild = false,
        )
    }

    single<MediaPlayerHandler>(createdAtStart = true) {
        WebMediaPlayerHandler()
    }

    single<StreamRepository> {
        object : StreamRepository {
            override suspend fun insertNewFormat(newFormat: NewFormatEntity) {}
            override fun getNewFormat(videoId: String): Flow<NewFormatEntity?> = flowOf(null)
            override suspend fun getFormatFlow(videoId: String): Flow<NewFormatEntity?> = flowOf(null)
            override suspend fun updateFormat(videoId: String) {}
            override fun getStream(
                dataStoreManager: DataStoreManager,
                videoId: String,
                isDownloading: Boolean,
                isVideo: Boolean,
                muxed: Boolean,
            ): Flow<String?> = flowOf("/api/stream?id=$videoId")
            override fun initPlayback(
                playback: String,
                atr: String,
                watchTime: String,
                cpn: String,
                playlistId: String?,
            ): Flow<Pair<Int, Float>> = flowOf(Pair(200, 0f))
            override fun updateWatchTimeFull(watchTime: String, cpn: String, playlistId: String?): Flow<Int> = flowOf(200)
            override fun updateWatchTime(
                playbackTrackingVideostatsWatchtimeUrl: String,
                watchTimeList: ArrayList<Float>,
                cpn: String,
                playlistId: String?,
            ): Flow<Int> = flowOf(200)
            override fun getSkipSegments(videoId: String): Flow<Resource<List<SponsorSkipSegments>>> = flowOf(Resource.Success(emptyList()))
            override fun getFullMetadata(videoId: String): Flow<Resource<Track>> = flowOf(Resource.Error("Not implemented"))
            override fun is403Url(url: String): Flow<Boolean> = flowOf(false)
            override suspend fun invalidateFormat(videoId: String) {}
            override fun getExtractSource(videoId: String): String? = "Termux SABR Backend"
        }
    }

    single<SearchRepository> {
        FakeSearchRepository()
    }

    single<HomeRepository> {
        FakeHomeRepository()
    }

    single<UpdateRepository> {
        FakeUpdateRepository()
    }

    single<LyricsCanvasRepository> {
        FakeLyricsCanvasRepository()
    }

    single<SongRepository> {
        FakeSongRepository()
    }

    single<AlbumRepository> {
        FakeAlbumRepository()
    }

    single<PlaylistRepository> {
        FakePlaylistRepository()
    }

    single<LocalPlaylistRepository> {
        FakeLocalPlaylistRepository(get())
    }

    single<CommonRepository> {
        FakeCommonRepository(get(), get())
    }

    single<AnalyticsRepository> {
        FakeAnalyticsRepository(get())
    }

    single<DownloadHandler> {
        WebDownloadHandler()
    }

    single<AccountRepository> {
        FakeAccountRepository()
    }

    single<PodcastRepository> {
        FakePodcastRepository()
    }

    single<AutoEqRepository> {
        FakeAutoEqRepository()
    }

    single<LibrarySyncRepository> {
        FakeLibrarySyncRepository()
    }

    single<BrowserLoginRepository> {
        FakeBrowserLoginRepository()
    }

    single<ImportRepository> {
        FakeImportRepository()
    }

    single<TasteRepository> {
        FakeTasteRepository()
    }

    single<LibrarySyncHostRepository> {
        FakeLibrarySyncHostRepository()
    }

    single<LibrarySyncPhoneRepository> {
        FakeLibrarySyncPhoneRepository()
    }

    single<LoginSyncHostRepository> {
        FakeLoginSyncHostRepository()
    }

    single<LoginSyncSenderRepository> {
        FakeLoginSyncSenderRepository()
    }

    single<CacheRepository> {
        FakeCacheRepository()
    }

    single<DiscordRepository> {
        FakeDiscordRepository()
    }

    single<ListenTogetherRepository> {
        FakeListenTogetherRepository()
    }

    single<LyricsRomanizerRepository> {
        FakeLyricsRomanizerRepository()
    }

    single<ArtistRepository> {
        FakeArtistRepository()
    }

    single {
        com.maxrave.data.listentogether.ListenTogetherPlaybackBridge()
    }
}

fun initWebKoin() {
    startKoin {
        modules(
            webAppModule,
            viewModelModule,
            com.streamify.sync.v5.di.syncV5Module,
        )
    }
}
