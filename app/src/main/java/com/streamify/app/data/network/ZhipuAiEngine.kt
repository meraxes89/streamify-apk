package com.streamify.app.data.network

import com.streamify.app.BuildConfig
import com.streamify.app.data.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zhipu AI (GLM-4-Flash) engine.
 *
 * SEC-08: the hardcoded `FALLBACK_KEYS` pool and the provider endpoint
 * literal were removed from source — five billable API keys used to ship
 * inside every APK's DEX, recoverable by anyone who unzipped it. Key
 * material now comes exclusively from configuration, mirroring the native
 * bridge's SEC-03 contract:
 *
 *  1. Runtime pool: the `STREAMIFY_ZHIPU_KEYS` process environment,
 *     proxied by [NativeBridge.getZhipuKey] (the bridge wraps the index
 *     modulo its own pool size, so the raw round-robin counter is fine).
 *  2. Build-time pool: the comma-separated `ZHIPU_API_KEYS` BuildConfig
 *     field, injected from `local.properties` or the same
 *     `STREAMIFY_ZHIPU_KEYS` environment variable at build time. Empty
 *     unless deliberately injected.
 *
 * The endpoint URL is likewise build-time injected (`ZHIPU_API_URL`,
 * default empty). With nothing configured, [isConfigured] is false and
 * [complete] returns null — callers already treat null as "AI assist
 * unavailable" and use their deterministic fallback path, so an
 * unconfigured build simply ships with AI features off (Part 5 of the
 * lead plan: optional AI that costs money ships off by default).
 */
object ZhipuAiEngine {
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private val atomicKeyIndex = AtomicInteger(0)

    /** Build-time key pool (comma-separated), empty unless injected (SEC-08). */
    private val buildTimeKeys: List<String> = BuildConfig.ZHIPU_API_KEYS
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** True when an endpoint and at least one usable key are configured. */
    val isConfigured: Boolean
        get() = BuildConfig.ZHIPU_API_URL.isNotBlank() &&
                (buildTimeKeys.isNotEmpty() || nativeKeyAt(0) != null)

    /**
     * Runtime key from the native bridge's `STREAMIFY_ZHIPU_KEYS` pool, or
     * null when the bridge is unavailable (JVM unit tests, unconfigured
     * process environment). Never falls back to embedded credentials.
     */
    private fun nativeKeyAt(index: Int): String? = try {
        NativeBridge.getZhipuKey(index).takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }

    private fun nextKey(): String? {
        val index = atomicKeyIndex.getAndIncrement()
        nativeKeyAt(index)?.let { return it }
        if (buildTimeKeys.isEmpty()) return null
        return buildTimeKeys[Math.floorMod(index, buildTimeKeys.size)]
    }

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.3,
        maxTokens: Int = 512
    ): String? = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.ZHIPU_API_URL
        if (endpoint.isBlank()) return@withContext null

        // Try the current key and up to one retry on the next key for
        // rate limits / transient network failures. With no pool
        // configured, degrade immediately: null == AI assist off.
        for (attempt in 0 until 2) {
            val key = nextKey() ?: return@withContext null

            try {
                val jsonBody = JSONObject().apply {
                    put("model", "glm-4-flash")
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userPrompt))
                    })
                    put("temperature", temperature)
                    put("max_tokens", maxTokens)
                }.toString()

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null

                    val bodyStr = response.body?.string() ?: return@use null
                    val json = JSONObject(bodyStr)
                    val choices = json.optJSONArray("choices") ?: return@use null
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        val content = message?.optString("content", "")
                        if (!content.isNullOrBlank()) {
                            return@withContext content.trim()
                        }
                    }
                    null
                }
            } catch (e: Exception) {
                // Try next key on retry
            }
        }
        null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
