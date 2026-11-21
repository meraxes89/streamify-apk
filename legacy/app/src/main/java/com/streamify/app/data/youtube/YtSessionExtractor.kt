package com.streamify.app.data.youtube
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamify.app.data.NativeBridge
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class YtSessionExtractor(private val context: Context) {
    private val isIntercepted = AtomicBoolean(false)
    private var pollingJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun launchAuthSession(
        webView: WebView,
        onSuccess: (authHeader: String, rawCookies: String) -> Unit,
        onError: (String) -> Unit
    ) {
        isIntercepted.set(false)
        release()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        }

        fun checkAndHarvestCookies() {
            if (isIntercepted.get()) return
            cookieManager.flush()

            // Query all candidate domains that carry Google/YouTube authorization state
            val ytMusicCookies = cookieManager.getCookie("https://music.youtube.com").orEmpty()
            val ytBaseCookies = cookieManager.getCookie("https://www.youtube.com").orEmpty()
            val ytDotCookies = cookieManager.getCookie("https://.youtube.com").orEmpty()
            val ytPlainCookies = cookieManager.getCookie("https://youtube.com").orEmpty()
            val googleAccountsCookies = cookieManager.getCookie("https://accounts.google.com").orEmpty()
            val googleDotCookies = cookieManager.getCookie("https://.google.com").orEmpty()
            val googlePlainCookies = cookieManager.getCookie("https://google.com").orEmpty()

            val aggregatedCookies = listOf(
                ytMusicCookies,
                ytBaseCookies,
                ytDotCookies,
                ytPlainCookies,
                googleAccountsCookies,
                googleDotCookies,
                googlePlainCookies
            ).filter { it.isNotBlank() }.joinToString("; ")

            val sapisid = extractCookieValue(aggregatedCookies, "SAPISID")
                ?: extractCookieValue(aggregatedCookies, "__Secure-3PAPISID")
                ?: extractCookieValue(aggregatedCookies, "__Secure-1PAPISID")

            if (!sapisid.isNullOrBlank()) {
                val origin = "https://music.youtube.com"
                val authHash = NativeBridge.generateSapisidHash(sapisid, origin)
                if (authHash != null && isIntercepted.compareAndSet(false, true)) {
                    release()
                    val fullAuthHeader = if (authHash.startsWith("SAPISIDHASH ")) authHash else "SAPISIDHASH $authHash"
                    try {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                    } catch (e: Exception) {
                        // Ignore
                    }
                    onSuccess(fullAuthHeader, aggregatedCookies)
                }
            }
        }

        // 1. Start continuous 800ms SPA Cookie Polling Daemon
        pollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && !isIntercepted.get()) {
                delay(800)
                checkAndHarvestCookies()
            }
        }

        // 2. Attach lifecycle listeners for standard page transitions
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                checkAndHarvestCookies()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkAndHarvestCookies()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkAndHarvestCookies()
            }
        }

        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com")
    }

    fun release() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Correctly handles cookie values containing '=' (Base64 padding, hashes, etc.)
     */
    private fun extractCookieValue(cookieHeader: String, key: String): String? {
        val tokens = cookieHeader.split(";")
        for (token in tokens) {
            val trimmed = token.trim()
            val separatorIndex = trimmed.indexOf('=')
            if (separatorIndex != -1) {
                val cookieKey = trimmed.substring(0, separatorIndex).trim()
                if (cookieKey.equals(key, ignoreCase = true)) {
                    return trimmed.substring(separatorIndex + 1).trim()
                }
            }
        }
        return null
    }
}
