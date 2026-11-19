package com.streamify.app.data.update
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.streamify.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import com.streamify.app.util.ApkSignatureVerifier
import com.streamify.app.util.SLog

data class RemoteBuildInfo(
    val buildNumber: Int,
    val tagName: String,
    val releaseTitle: String,
    val changelog: String,
    val apkDownloadUrl: String?,
    val releaseHtmlUrl: String,
    val publishedAt: String = ""
)

sealed interface UpdateState {
    object Idle : UpdateState
    data class Checking(val isManual: Boolean = false) : UpdateState
    data class UpToDate(val currentBuild: Int, val isManual: Boolean = false) : UpdateState
    data class UpdateAvailable(
        val buildInfo: RemoteBuildInfo,
        val isManual: Boolean = false
    ) : UpdateState
    data class Downloading(val buildInfo: RemoteBuildInfo, val downloadId: Long) : UpdateState
    data class Error(val message: String, val isManual: Boolean = false) : UpdateState
}

object StreamifyUpdateManager {

    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/zephyr4289/streamify-apk/releases?per_page=5"
    private const val PREFS_NAME = "streamify_updates"
    private const val KEY_ETAG = "github_release_etag"
    private const val KEY_DISMISSED_BUILD = "dismissed_build_number"

    private val BUILD_REGEX = Pattern.compile("(?:build-|v|release-)?(\\d+)", Pattern.CASE_INSENSITIVE)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var downloadReceiverRegistered = false

    /**
     * Dual-Trigger Update Checker:
     * - Silent on Cold Launch (isManual = false)
     * - Interactive on Settings Tap (isManual = true)
     */
    suspend fun checkForUpdates(context: Context, isManual: Boolean = false) {
        _updateState.value = UpdateState.Checking(isManual)
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val cachedEtag = prefs.getString(KEY_ETAG, null)

                val url = URL(GITHUB_RELEASES_API)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "Streamify-App/${BuildConfig.VERSION_CODE}")
                    if (!isManual && !cachedEtag.isNullOrBlank()) {
                        setRequestProperty("If-None-Match", cachedEtag)
                    }
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    // HTTP 304 Not Modified: 0 rate limit cost
                    _updateState.value = if (isManual) {
                        UpdateState.UpToDate(BuildConfig.VERSION_CODE, isManual = true)
                    } else {
                        UpdateState.Idle
                    }
                    return@withContext
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val newEtag = connection.getHeaderField("ETag")
                    if (!newEtag.isNullOrBlank()) {
                        prefs.edit().putString(KEY_ETAG, newEtag).apply()
                    }

                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val releasesArray = JSONArray(responseText)
                    val bestBuild = parseHighestRelease(releasesArray)

                    if (bestBuild != null && isNewerBuild(bestBuild.buildNumber, BuildConfig.VERSION_CODE)) {
                        val dismissedBuild = prefs.getInt(KEY_DISMISSED_BUILD, -1)
                        if (!isManual && dismissedBuild >= bestBuild.buildNumber) {
                            _updateState.value = UpdateState.Idle
                        } else {
                            _updateState.value = UpdateState.UpdateAvailable(
                                buildInfo = bestBuild,
                                isManual = isManual
                            )
                        }
                    } else {
                        _updateState.value = if (isManual) {
                            UpdateState.UpToDate(BuildConfig.VERSION_CODE, isManual = true)
                        } else {
                            UpdateState.Idle
                        }
                    }
                } else {
                    val errorMsg = if (responseCode == 403) {
                        "GitHub rate limit reached. Try again later."
                    } else {
                        "Server returned HTTP $responseCode"
                    }
                    _updateState.value = if (isManual) {
                        UpdateState.Error(errorMsg, isManual = true)
                    } else {
                        UpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                _updateState.value = if (isManual) {
                    UpdateState.Error(e.message ?: "Network error while checking updates", isManual = true)
                } else {
                    UpdateState.Idle
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Traverses the releases array and extracts the release with the highest integer build run number.
     */
    private fun parseHighestRelease(releases: JSONArray): RemoteBuildInfo? {
        var topBuildInfo: RemoteBuildInfo? = null
        var maxBuildNumber = -1

        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            val tagName = rel.optString("tag_name", "")
            val isPrerelease = rel.optBoolean("prerelease", false)

            // Production updates ignore Canary and experimental prerelease tags
            if (tagName.startsWith("canary-", ignoreCase = true) || isPrerelease) {
                continue
            }

            val title = rel.optString("name", tagName)
            val changelog = rel.optString("body", "Bug fixes and performance improvements.")
            val htmlUrl = rel.optString("html_url", "https://github.com/zephyr4289/streamify-apk/releases")
            val publishedAt = rel.optString("published_at", "")

            // Extract numeric build ID from tag name (e.g., "build-114" -> 114) or title
            val buildNum = extractNumericBuild(tagName).takeIf { it > 0 } ?: extractNumericBuild(title)

            // Look for attached streamify.apk asset
            val assets = rel.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", null)
                        break
                    }
                }
            }

            if (buildNum > maxBuildNumber) {
                maxBuildNumber = buildNum
                topBuildInfo = RemoteBuildInfo(
                    buildNumber = buildNum,
                    tagName = tagName,
                    releaseTitle = title,
                    changelog = changelog,
                    apkDownloadUrl = apkUrl,
                    releaseHtmlUrl = htmlUrl,
                    publishedAt = publishedAt
                )
            }
        }

        return topBuildInfo
    }

    private fun extractNumericBuild(text: String): Int {
        val matcher = BUILD_REGEX.matcher(text.trim())
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull() ?: 0
        } else {
            0
        }
    }

    private fun isNewerBuild(remoteBuild: Int, currentVersionCode: Int): Boolean {
        return remoteBuild > currentVersionCode
    }

    /**
     * Dispatches the update action:
     * - If direct APK asset is available, triggers DownloadManager with completion installer receiver.
     * - Falls back to opening the GitHub release web page in browser.
     */
    fun dispatchUpdate(context: Context, buildInfo: RemoteBuildInfo) {
        val directUrl = buildInfo.apkDownloadUrl
        if (!directUrl.isNullOrBlank()) {
            try {
                val fileName = "Streamify-Build-${buildInfo.buildNumber}.apk"
                val request = DownloadManager.Request(Uri.parse(directUrl)).apply {
                    setTitle("Streamify Update (Build ${buildInfo.buildNumber})")
                    setDescription("Downloading latest APK build...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setMimeType("application/vnd.android.package-archive")
                }

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = downloadManager.enqueue(request)

                _updateState.value = UpdateState.Downloading(buildInfo, downloadId)
                registerDownloadReceiver(context.applicationContext, downloadId, fileName)
                return
            } catch (e: Exception) {
                SLog.st("StreamifyUpdateManager", "StreamifyUpdateManager.dispatchUpdate failed", e)
            }
        }

        // Fallback: Open GitHub Release page in browser
        openReleasePage(context, buildInfo.releaseHtmlUrl)
    }

    fun openReleasePage(context: Context, releaseUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            SLog.st("StreamifyUpdateManager", "StreamifyUpdateManager.openReleasePage failed", e)
        }
    }

    fun dismissUpdate(context: Context, buildNumber: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DISMISSED_BUILD, buildNumber).apply()
        _updateState.value = UpdateState.Idle
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    private fun registerDownloadReceiver(appContext: Context, targetDownloadId: Long, fileName: String) {
        if (downloadReceiverRegistered) return
        downloadReceiverRegistered = true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == targetDownloadId && ctx != null) {
                        try {
                            val downloadFile = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                fileName
                            )
                            if (downloadFile.exists()) {
                                installApk(ctx, downloadFile)
                            }
                        } catch (e: Exception) {
                            SLog.st("StreamifyUpdateManager", "StreamifyUpdateManager.onReceive failed", e)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun installApk(context: Context, file: File) {
        // SEC-04: pin the updater certificate — inspect the downloaded
        // archive's package signature BEFORE handing it to the system
        // package installer, so a tampered or hijacked update can never
        // reach the install surface.
        val verdict = ApkSignatureVerifier.verifyUpdateCertificate(context, file)
        if (verdict is ApkSignatureVerifier.Verification.Rejected) {
            SLog.st(
                "StreamifyUpdateManager",
                "SEC-04 blocked update ${file.name}: ${verdict.reason}",
                null
            )
            _updateState.value = UpdateState.Error(
                "Update rejected: ${verdict.reason}",
                isManual = false
            )
            // Best-effort removal of the hostile artifact; even when the
            // public Downloads file cannot be deleted on scoped storage,
            // the installer intent below is never fired.
            runCatching { file.delete() }
            return
        }
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            SLog.st("StreamifyUpdateManager", "StreamifyUpdateManager.installApk failed", e)
        }
    }
}
