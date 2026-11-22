package com.maxrave.simpmusic.data

import com.maxrave.domain.extension.now
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remote Dynamic Greeting & Messaging Engine
 *
 * Fetches dynamic time-of-day greetings and holiday overrides from GitHub raw CDN:
 * https://raw.githubusercontent.com/meraxes89/streamify-apk/main/scripts/remote-config/greetings.json
 *
 * Guarantees:
 * 1. 0ms UI blocking: Immediately serves in-memory cache or bundled default fallback.
 * 2. Background Stale-While-Revalidate (SWR): Queries remote config in background without freezing app startup.
 * 3. Handles midnight-wrapping hour ranges (e.g., 22:00 -> 04:59).
 * 4. Eliminates awkward "Good night" salutations when users open the app late at night.
 */
object RemoteGreetingManager {

    private const val TAG = "RemoteGreetingManager"
    private const val REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/meraxes89/streamify-apk/main/scripts/remote-config/greetings.json"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val defaultFallbackConfig = RemoteGreetingConfig(
        version = 1,
        slots = listOf(
            GreetingSlot(
                id = "morning",
                start_hour = 5,
                end_hour = 11,
                messages = mapOf("default" to listOf("Good morning")),
            ),
            GreetingSlot(
                id = "afternoon",
                start_hour = 12,
                end_hour = 16,
                messages = mapOf("default" to listOf("Good afternoon")),
            ),
            GreetingSlot(
                id = "evening",
                start_hour = 17,
                end_hour = 21,
                messages = mapOf("default" to listOf("Good evening")),
            ),
            GreetingSlot(
                id = "late_night",
                start_hour = 22,
                end_hour = 4,
                messages = mapOf("default" to listOf("Late night vibes", "Good evening")),
            ),
        ),
    )

    private var activeConfig: RemoteGreetingConfig = defaultFallbackConfig
    private val _greetingFlow = MutableStateFlow(resolveGreeting(now().time.hour))
    val greetingFlow: StateFlow<String> = _greetingFlow.asStateFlow()

    private var lastFetchTimeMillis: Long = 0L
    private const val FETCH_COOLDOWN_MS = 60 * 60 * 1000L // 1 hour TTL

    /**
     * Resolves the current greeting based on active time of day and calendar date.
     */
    fun getGreeting(hour: Int = now().time.hour): String {
        return resolveGreeting(hour)
    }

    private fun resolveGreeting(hour: Int): String {
        val currentDate = now().date
        val currentMonth = currentDate.monthNumber
        val currentDay = currentDate.dayOfMonth

        // 1. Check special calendar event overrides first
        activeConfig.special_events.firstOrNull { it.month == currentMonth && it.day == currentDay }?.let { event ->
            val eventPhrases = event.messages["en"] ?: event.messages["default"]
            if (!eventPhrases.isNullOrEmpty()) {
                return eventPhrases.first()
            }
        }

        // 2. Check time-of-day slots
        val matchedSlot = activeConfig.slots.firstOrNull { slot ->
            isHourInSlot(hour, slot.start_hour, slot.end_hour)
        }

        val phrases = matchedSlot?.messages?.get("en")
            ?: matchedSlot?.messages?.get("default")
            ?: defaultFallbackForHour(hour)

        return if (phrases.isNotEmpty()) {
            val index = (hour + currentDay) % phrases.size
            phrases[index]
        } else {
            "Good day"
        }
    }

    private fun isHourInSlot(hour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            hour in start..end
        } else {
            // Wraps past midnight (e.g., 22:00 -> 04:59)
            hour >= start || hour <= end
        }
    }

    private fun defaultFallbackForHour(hour: Int): List<String> {
        return when {
            hour in 5..11 -> listOf("Good morning")
            hour in 12..16 -> listOf("Good afternoon")
            hour in 17..21 -> listOf("Good evening")
            else -> listOf("Late night vibes", "Good evening")
        }
    }

    /**
     * Asynchronously fetches the latest config from GitHub CDN.
     */
    suspend fun syncRemoteConfig() {
        val currentTime = com.maxrave.simpmusic.ui.screen.other.nowEpochMillis()
        if (currentTime - lastFetchTimeMillis < FETCH_COOLDOWN_MS && activeConfig !== defaultFallbackConfig) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 6000
                        connectTimeoutMillis = 4000
                    }
                }
                val response = client.get(REMOTE_CONFIG_URL)
                if (response.status == HttpStatusCode.OK) {
                    val rawBody = response.bodyAsText()
                    val parsed = jsonParser.decodeFromString<RemoteGreetingConfig>(rawBody)
                    if (parsed.slots.isNotEmpty()) {
                        activeConfig = parsed
                        lastFetchTimeMillis = currentTime
                        _greetingFlow.value = resolveGreeting(now().time.hour)
                        Logger.d(TAG, "Successfully updated remote greetings config (v${parsed.version})")
                    }
                }
                client.close()
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to sync remote greetings, retaining active cache: ${e.message}")
            }
        }
    }
}

@Serializable
data class RemoteGreetingConfig(
    val version: Int = 1,
    val slots: List<GreetingSlot> = emptyList(),
    val special_events: List<SpecialEvent> = emptyList(),
)

@Serializable
data class GreetingSlot(
    val id: String,
    val start_hour: Int,
    val end_hour: Int,
    val messages: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class SpecialEvent(
    val id: String,
    val month: Int,
    val day: Int,
    val messages: Map<String, List<String>> = emptyMap(),
)
