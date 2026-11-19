package com.streamify.app.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FleetConfig — remote, release-free adaptation of the Innertube client fleet.
 *
 * YouTube's historical breakage modes (fingerprint rotting, STS drift, client
 * walls) are DATA, not code. This component fetches a tiny schema-guarded JSON
 * (<= 10KB) from the repository and merges it over the baked-in defaults, so a
 * walled client version can be rotated server-side within the TTL — no APK
 * release required. The daily resolver-canary CI job writes this file when its
 * probes detect drift, closing the detection -> remediation loop.
 *
 * Hard guarantees:
 *  - Network/parse/validation failure NEVER changes behavior: last-good config
 *    is kept, and baked-in defaults always remain the final fallback.
 *  - Only whitelisted fields pass ingestion; nothing from the network can
 *    introduce arbitrary URLs, commands or code.
 */
object FleetConfig {

    private const val TAG = "FleetConfig"
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/zephyr4289/streamify-apk/main/fleet-config.json"
    private const val PREFS = "fleet_config"
    private const val KEY_JSON = "last_good_json"
    private const val KEY_FETCHED_AT = "fetched_at_ms"
    private const val TTL_MS = 6L * 60 * 60 * 1000 // 6 hours

    /** Hard response cap — anything larger is rejected outright. */
    private const val MAX_BODY_BYTES = 10_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    var current: FleetData? = null
        private set

    data class ClientSpec(
        val clientName: String,
        val clientVersion: String,
        val clientNumber: String,
        val userAgent: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null
    )

    data class FleetData(
        val version: Long,
        val signatureTimestamp: Int,
        val audioClients: List<ClientSpec>,
        val videoClients: List<ClientSpec>,
        /** Innertube search client overrides: music (WEB_REMIX) + web (WEB). */
        val musicSearchVersion: String?,
        val webSearchVersion: String?,
        val fetchedAtMs: Long
    )

    // ------------------------------------------------------------- lifecycle

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 1. Instant-apply last good config from disk (survives restarts offline).
        prefs?.getString(KEY_JSON, null)?.let { cached ->
            parseAndAdopt(cached, System.currentTimeMillis())?.let {
                current = it
                logAdoption(it, "disk-cache")
            }
        }
        // 2. Background refresh honoring TTL.
        scope.launch { runCatching { refresh() } }
    }

    suspend fun refresh(force: Boolean = false) {
        val fetchedAt = prefs?.getLong(KEY_FETCHED_AT, 0L) ?: 0L
        if (!force && System.currentTimeMillis() - fetchedAt < TTL_MS && current != null) return
        val body = withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(CONFIG_URL).build()
                com.streamify.app.data.network.NetworkEngine.client.newCall(req).execute()
                    .use { resp ->
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: throw Exception("empty body")
                        if (bytes.size > MAX_BODY_BYTES) throw Exception("body too large (${bytes.size})")
                        String(bytes, Charsets.UTF_8)
                    }
            }
        }.getOrElse {
            SLog.w(TAG, "refresh failed (${it.message}) — keeping existing config")
            return
        }
        val adopted = parseAndAdopt(body, System.currentTimeMillis())
        if (adopted != null) {
            prefs?.edit()?.putString(KEY_JSON, body)?.putLong(KEY_FETCHED_AT, System.currentTimeMillis())?.apply()
            logAdoption(adopted, "network")
        } else {
            SLog.e(TAG, "rejected invalid fleet-config payload")
        }
    }

    // ------------------------------------------------------------ merge APIs

    fun audioTargets(defaults: List<ClientSpec>): List<ClientSpec> =
        current?.audioClients?.takeIf { it.isNotEmpty() } ?: defaults

    fun videoTargets(defaults: List<ClientSpec>): List<ClientSpec> =
        current?.videoClients?.takeIf { it.isNotEmpty() } ?: defaults

    fun signatureTimestamp(fallback: Int): Int =
        current?.signatureTimestamp?.takeIf { it in 1000..999_999 } ?: fallback

    /** Search client versions (release-free adaptation of the search endpoint). */
    fun musicSearchVersion(fallback: String): String =
        current?.musicSearchVersion?.takeIf { it.matches(SEARCH_VER_REGEX) } ?: fallback

    fun webSearchVersion(fallback: String): String =
        current?.webSearchVersion?.takeIf { it.matches(SEARCH_VER_REGEX) } ?: fallback

    fun statusLine(): String {
        val c = current ?: return "baked-defaults"
        val ts = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(c.fetchedAtMs))
        return "v${c.version} sts=${c.signatureTimestamp} audio=${c.audioClients.size} video=${c.videoClients.size} @ $ts"
    }

    // ------------------------------------------------------------ ingestion

    private val ALLOWED_CLIENTS =
        setOf("ANDROID", "IOS", "ANDROID_VR", "WEB_REMIX", "VISIONOS", "TVHTML5")

    /** Innertube client versions look like 1.20240101.01.00 / 2.20260708.05.00. */
    private val SEARCH_VER_REGEX = Regex("""\d{1,2}\.\d{8}\.\d{2}\.\d{2}""")
    private val SEARCH_VER_MAX = 32

    /** Returns adopted FleetData, or null when anything fails validation. */
    private fun parseAndAdopt(body: String, nowMs: Long): FleetData? = try {
        val root = JSONObject(body)
        val version = root.getLong("version")
        val sts = root.getInt("signatureTimestamp")
        require(version in 1..9_999_999L) { "bad version" }
        require(sts in 1000..999_999) { "bad STS" }

        val audio = parseClients(root.getJSONArray("audioClients"))
        val video = parseClients(root.optJSONArray("videoClients") ?: root.getJSONArray("audioClients"))
        require(audio.isNotEmpty()) { "empty audio fleet" }

        val musicSearch = cappedOpt(root.optJSONObject("searchClients")?.optString("musicVersion"), SEARCH_VER_MAX)
        val webSearch = cappedOpt(root.optJSONObject("searchClients")?.optString("webVersion"), SEARCH_VER_MAX)
        if (musicSearch != null && !musicSearch.matches(SEARCH_VER_REGEX)) throw Exception("bad musicVersion")
        if (webSearch != null && !webSearch.matches(SEARCH_VER_REGEX)) throw Exception("bad webVersion")

        FleetData(version, sts, audio, video, musicSearch, webSearch, nowMs)
    } catch (t: Throwable) {
        SLog.e(TAG, "validation error: ${t.message}")
        null
    }

    private fun parseClients(arr: JSONArray): List<ClientSpec> {
        val out = mutableListOf<ClientSpec>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val o = arr.getJSONObject(i)
            val name = capped(o.optString("clientName"), 16).uppercase(Locale.US)
            require(name in ALLOWED_CLIENTS) { "client not allowed: $name" }
            out.add(
                ClientSpec(
                    clientName = name,
                    clientVersion = capped(o.optString("clientVersion"), 32),
                    clientNumber = capped(o.optString("clientNumber"), 3),
                    userAgent = capped(o.optString("userAgent"), 200),
                    deviceMake = cappedOpt(o.optString("deviceMake"), 24),
                    deviceModel = cappedOpt(o.optString("deviceModel"), 24),
                    osName = cappedOpt(o.optString("osName"), 16),
                    osVersion = cappedOpt(o.optString("osVersion"), 16)
                )
            )
        }
        return out
    }

    private fun capped(v: String, max: Int): String {
        require(v.isNotBlank()) { "blank field" }
        require(!v.contains('\n') && !v.contains('\r')) { "newline in field" }
        return v.trim().take(max)
    }

    private fun cappedOpt(v: String?, max: Int): String? {
        val t = v?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return capped(t, max)
    }

    private fun logAdoption(d: FleetData, source: String) {
        SLog.i(TAG, "adopted $source → ${statusLine()}")
    }
}
