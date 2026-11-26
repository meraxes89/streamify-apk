package com.maxrave.simpmusic

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.di.DemoCatalogLoader
import com.maxrave.simpmusic.di.initWebKoin
import com.maxrave.simpmusic.utils.StreamifyDefaultsInitializer
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin

private val appStartupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    try {
        initWebKoin()
        appStartupScope.launch {
            try {
                // Initialize default preferences
                val dataStoreManager: DataStoreManager = getKoin().get()
                StreamifyDefaultsInitializer.initialize(dataStoreManager)
            } catch (e: Throwable) {
                println("StreamifyDefaultsInitializer error: $e")
            }

            try {
                // Pre-fetch demo catalog snapshot from CDN / cache for standalone GitHub Pages mode
                DemoCatalogLoader.load()
            } catch (e: Throwable) {
                println("DemoCatalogLoader init error: $e")
            }
        }
        ComposeViewport(document.body!!) {
            androidx.compose.runtime.SideEffect {
                document.getElementById("loading")?.remove()
            }
            App()
        }
    } catch (e: Throwable) {
        val errDiv = document.getElementById("loading")
        if (errDiv != null) {
            errDiv.textContent = "App Error: ${e.message ?: e::class.simpleName}"
        }
    }
}
