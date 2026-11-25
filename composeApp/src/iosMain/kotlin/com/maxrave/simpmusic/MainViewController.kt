package com.maxrave.simpmusic

import androidx.compose.ui.window.ComposeUIViewController
import com.maxrave.common.AppIdentity
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.simpmusic.di.viewModelModule
import com.streamify.jam.di.streamifyJamModule
import com.streamify.lyrics.slyr.di.streamifyLyricsSlyrModule
import com.streamify.media.hal.di.mediaHalModule
import com.streamify.supabase.di.supabaseModule
import org.koin.core.context.startKoin

fun initKoinIos(versionName: String = BuildKonfig.versionName) {
    startKoin {
        loadAllModules(
            AppIdentity(
                applicationId = "com.maxrave.simpmusic",
                versionName = versionName,
                platform = "iOS",
            ),
        )
        modules(viewModelModule)
        modules(mediaHalModule, streamifyLyricsSlyrModule, supabaseModule, streamifyJamModule)
    }
}

fun MainViewController() = ComposeUIViewController { App() }
