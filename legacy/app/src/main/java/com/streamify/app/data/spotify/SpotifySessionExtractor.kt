package com.streamify.app.data.spotify
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamify.app.data.network.NetworkEngine
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class SpotifySessionExtractor(private val context: Context) {
    private val isIntercepted = AtomicBoolean(false)
    private var pollingJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun launchAuthSession(
        webView: WebView,
        onSuccess: (accessToken: String, spDcCookie: String) -> Unit,
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

        fun checkAndHarvestSpDc() {
            if (isIntercepted.get()) return
            cookieManager.flush()

            val openCookies = cookieManager.getCookie("https://open.spotify.com").orEmpty()
            val baseCookies = cookieManager.getCookie("https://accounts.spotify.com").orEmpty()
            val dotCookies = cookieManager.getCookie("https://.spotify.com").orEmpty()
            val domainCookies = cookieManager.getCookie("https://spotify.com").orEmpty()
            val aggregated = listOf(openCookies, baseCookies, dotCookies, domainCookies)
                .filter { it.isNotBlank() }
                .joinToString("; ")

            val spDc = extractCookie(aggregated, "sp_dc")

            if (!spDc.isNullOrBlank() && isIntercepted.compareAndSet(false, true)) {
                release()
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                } catch (e: Exception) {
                    // Ignore
                }

                // Fetch dynamic web player Bearer Token using the harvested sp_dc cookie
                CoroutineScope(Dispatchers.IO).launch {
                    val tokenResult = fetchWebAccessToken(spDc)
                    withContext(Dispatchers.Main) {
                        tokenResult.onSuccess { token ->
                            onSuccess(token, spDc)
                        }.onFailure { err ->
                            onError(err.message ?: "Failed to generate web token")
                        }
                    }
                }
            }
        }

        // 1. Start continuous 800ms SPA Cookie Polling Daemon
        pollingJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && !isIntercepted.get()) {
                delay(800)
                checkAndHarvestSpDc()
            }
        }

        // 2. Attach lifecycle listeners for standard page transitions
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                checkAndHarvestSpDc()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkAndHarvestSpDc()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkAndHarvestSpDc()
            }
        }

        webView.loadUrl("https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F")
    }

    fun release() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun fetchWebAccessToken(spDc: String): Result<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36")
            .header("Cookie", "sp_dc=$spDc")
            .build()

        try {
            val response = NetworkEngine.client.newCall(request).execute()
            val rawJson = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Spotify token response failed HTTP ${response.code}"))
            }

            val json = JSONObject(rawJson)
            if (json.optBoolean("isAnonymous", true)) {
                return@withContext Result.failure(Exception("Session expired or invalid sp_dc cookie"))
            }

            val accessToken = json.getString("accessToken")
            Result.success(accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractCookie(cookieHeader: String, key: String): String? {
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
