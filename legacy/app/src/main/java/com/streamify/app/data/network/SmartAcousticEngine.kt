package com.streamify.app.data.network

import android.util.LruCache
import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class SmartEqProfile(
    val trackId: Int,
    val bandGainsDb: IntArray, // 10 bands: [60Hz, 170Hz, 310Hz, 600Hz, 1kHz, 3kHz, 6kHz, 12kHz, 14kHz, 16kHz]
    val profileName: String
)

/**
 * Smart Acoustic EQ & Spatial Atmosphere Matching Engine
 * Uses GLM-4-Flash to dynamically generate acoustic mastering EQ curves (-12dB to +12dB)
 * tailored to the track's genre, tempo, and sonic character.
 */
object SmartAcousticEngine {

    private val eqCache = LruCache<String, SmartEqProfile>(64)

    suspend fun getSmartEqProfile(track: Track): SmartEqProfile = withContext(Dispatchers.IO) {
        val cacheKey = "${track.title}_${track.artist}_${track.genre}_${track.bpm.toInt()}".lowercase()
        val cached = eqCache.get(cacheKey)
        if (cached != null) return@withContext cached

        val systemPrompt = """
            You are a professional audio mastering engineer. Analyze the track genre, tempo, and style to produce a custom 10-band mastering EQ curve.
            Band frequencies: [60Hz, 170Hz, 310Hz, 600Hz, 1kHz, 3kHz, 6kHz, 12kHz, 14kHz, 16kHz]
            Values are integer gain in dB from -6 to +6.
            Return ONLY a JSON array of 10 integers.
            Example for Phonk/Bass: [5, 4, 2, 0, -1, 1, 2, 3, 4, 3]
            Example for Acoustic/Vocal: [-2, 0, 1, 2, 3, 2, 2, 3, 1, 0]
            No commentary, no markdown.
        """.trimIndent()

        val userPrompt = """
            Track: ${track.title} by ${track.artist}
            Genre: ${track.genre.ifBlank { "Modern" }}
            BPM: ${track.bpm.toInt()}
        """.trimIndent()

        val aiResponse = ZhipuAiEngine.complete(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.2,
            maxTokens = 64
        )

        if (!aiResponse.isNullOrBlank()) {
            try {
                val cleanedJson = aiResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val jsonArr = JSONArray(cleanedJson)
                if (jsonArr.length() >= 10) {
                    val gains = IntArray(10)
                    for (i in 0 until 10) {
                        gains[i] = jsonArr.getInt(i).coerceIn(-10, 10)
                    }
                    val profile = SmartEqProfile(
                        trackId = track.id,
                        bandGainsDb = gains,
                        profileName = "AI Adaptive: ${track.genre.ifBlank { "Dynamic" }}"
                    )
                    eqCache.put(cacheKey, profile)
                    return@withContext profile
                }
            } catch (e: Exception) {
                // Fallback below
            }
        }

        // Default dynamic loudness curve
        val defaultProfile = SmartEqProfile(
            trackId = track.id,
            bandGainsDb = intArrayOf(3, 2, 1, 0, 0, 1, 2, 2, 1, 1),
            profileName = "Natural Master"
        )
        eqCache.put(cacheKey, defaultProfile)
        defaultProfile
    }
}
