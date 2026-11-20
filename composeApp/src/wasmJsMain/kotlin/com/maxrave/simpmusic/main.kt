package com.maxrave.simpmusic

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.di.initWebKoin
import com.maxrave.simpmusic.utils.StreamifyDefaultsInitializer
import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin

@OptIn(ExperimentalComposeUiApi::class, DelicateCoroutinesApi::class)
fun main() {
    try {
        initWebKoin()
        GlobalScope.launch {
            try {
                val dataStoreManager: DataStoreManager = getKoin().get()
                StreamifyDefaultsInitializer.initialize(dataStoreManager)
            } catch (e: Throwable) {
                println("StreamifyDefaultsInitializer error: $e")
            }
        }
        val loadingEl = document.getElementById("loading")
        loadingEl?.remove()

        ComposeViewport(document.body!!) {
            App()
        }
    } catch (e: Throwable) {
        val errEl = document.createElement("div")
        errEl.setAttribute("style", "position:fixed;top:0;left:0;right:0;bottom:0;background:#1a0505;color:#ff6b6b;padding:24px;font-family:monospace;white-space:pre-wrap;z-index:999999;overflow:auto;font-size:14px;")
        errEl.textContent = "⚠️ Main Launch Error:\n" + (e.message ?: "Unknown error") + "\n\n" + e.stackTraceToString()
        document.body?.appendChild(errEl)
        println(e)
    }
}
