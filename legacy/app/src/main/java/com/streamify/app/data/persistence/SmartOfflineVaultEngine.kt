package com.streamify.app.data.persistence
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.NativeBridge

object SmartOfflineVaultEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private val isSyncing = AtomicBoolean(false)

    private val _vaultedTrackCount = MutableStateFlow(0)
    val vaultedTrackCount: StateFlow<Int> = _vaultedTrackCount.asStateFlow()

    private val memoryIndex = ConcurrentHashMap<String, String>() // Track Key -> Filepath

    fun initialize(context: Context) {
        loadMemoryIndex(context)
        triggerSmartSync(context)
    }

    private fun getVaultDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val vault = File(baseDir, "offline_vault")
        if (!vault.exists()) vault.mkdirs()
        return vault
    }

    private fun getTrackKey(track: Track): String {
        val cleanTitle = track.title.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val cleanArtist = track.artist.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
        return "vault_${cleanTitle}_${cleanArtist}"
    }

    private fun loadMemoryIndex(context: Context) {
        val vaultDir = getVaultDirectory(context)
        val files = vaultDir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.length() > 100 * 1024L) {
                val key = file.nameWithoutExtension
                memoryIndex[key] = file.absolutePath
            }
        }
        _vaultedTrackCount.value = memoryIndex.size
    }

    /**
     * Fast O(1) in-memory check for vaulted offline track file.
     */
    fun getOfflineTrack(track: Track, context: Context?): Track? {
        val key = getTrackKey(track)
        val path = memoryIndex[key] ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 50 * 1024L) {
            track.copy(filepath = file.absolutePath)
        } else {
            memoryIndex.remove(key)
            null
        }
    }

    fun isTrackVaulted(track: Track): Boolean {
        val key = getTrackKey(track)
        val path = memoryIndex[key] ?: return false
        return File(path).exists()
    }

    /**
     * Autonomous Predictive Wi-Fi & Charging Background Vaulting
     */
    fun triggerSmartSync(context: Context) {
        if (!isSyncing.compareAndSet(false, true)) return

        syncJob?.cancel()
        syncJob = scope.launch {
            try {
                // 1. Policy Gate: Check Wi-Fi & Battery Health
                val isWifi = isWifiConnected(context)
                val isHealthyPower = isChargingOrHighBattery(context)

                if (!isWifi && !isHealthyPower) {
                    return@launch
                }

                // 2. Select High-Affinity Targets (Top 15 Heavy Rotation + Circadian Slots)
                val allTracks = TrackRepository.allTracks.value
                val topRotation = allTracks.sortedByDescending { it.id }.take(15)
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val circadianRecs = try {
                    NativeBridge.getCircadianRecommendations(currentHour, 10).mapNotNull { rec ->
                        TrackRepository.allTracks.value.find { it.id == rec.trackId }
                    }
                } catch (e: Exception) {
                    emptyList<Track>()
                }

                val targets = (topRotation + circadianRecs).distinctBy { getTrackKey(it) }
                val vaultDir = getVaultDirectory(context)

                for (target in targets) {
                    val key = getTrackKey(target)
                    if (memoryIndex.containsKey(key) && File(memoryIndex[key] ?: "").exists()) {
                        continue
                    }

                    // Download stream chunk silently in background
                    vaultTrack(target, vaultDir, key)
                    delay(500) // Politeness throttle to protect bandwidth
                }

                _vaultedTrackCount.value = memoryIndex.size
            } catch (e: Exception) {
                // Non-fatal background sync error
            } finally {
                isSyncing.set(false)
            }
        }
    }

    private suspend fun vaultTrack(track: Track, vaultDir: File, key: String) = withContext(Dispatchers.IO) {
        try {
            val resolved = YouTubeStreamResolver.resolveTrackStream(track) ?: return@withContext
            val streamUrl = resolved.streamUrl
            if (streamUrl.isBlank() || streamUrl.startsWith("/") || streamUrl.startsWith("file://")) return@withContext

            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val targetFile = File(vaultDir, "$key.m4a")
            val tempFile = File(vaultDir, "$key.tmp")

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext
                val body = response.body ?: return@withContext

                FileOutputStream(tempFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 50 * 1024L) {
                tempFile.renameTo(targetFile)
                memoryIndex[key] = targetFile.absolutePath
            } else {
                tempFile.delete()
            }
        } catch (e: Exception) {
            // Silently skip if network dropped
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun isChargingOrHighBattery(context: Context): Boolean {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter) ?: return true
        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = level * 100 / scale.toFloat()
        return isCharging || batteryPct > 50f
    }
}
