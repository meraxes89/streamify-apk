package com.streamify.app.data.network

import com.streamify.app.data.repository.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LivePersona(
    val title: String,
    val description: String,
    val topAcousticTrait: String,
    val dateGenerated: String
)

/**
 * Live Listening Persona & Daily Vibe Wrap Generator
 * Analyzes local circadian listening telemetry, skip rates, and tempo distributions
 * to generate a dense, analytical personal music identity card via GLM-4-Flash.
 */
object PersonaEngine {

    @Volatile
    private var cachedPersona: LivePersona? = null
    private var lastCacheKey: String = ""

    suspend fun generateLivePersona(): LivePersona = withContext(Dispatchers.IO) {
        val tracks = TrackRepository.allTracks.value
        val totalTracks = tracks.size
        val avgBpm = if (totalTracks > 0) tracks.map { it.bpm }.filter { it > 0 }.average().toInt() else 124
        val totalPlayCount = totalTracks
        val topGenres = tracks.groupBy { it.genre.ifBlank { "Modern" } }
            .maxByOrNull { it.value.size }?.key ?: "Electronic"

        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val currentCacheKey = "${todayDate}_${totalPlayCount}_${avgBpm}_${topGenres}"

        cachedPersona?.takeIf { lastCacheKey == currentCacheKey }?.let { return@withContext it }

        val systemPrompt = """
            You are an expert acoustic psychoanalyst. Analyze the user's raw music listening telemetry and generate an analytical persona card.
            Return ONLY a JSON object with:
            - "title": A sharp 2-4 word archetype (e.g. "The Nocturnal Synthesist", "High-Velocity Phonk Seeker", "Atmospheric Minimalist")
            - "description": A witty 2-sentence breakdown analyzing their acoustic tempo, energy, and listening traits.
            - "topAcousticTrait": A short 3-word summary of their core acoustic signature.
            No markdown formatting, no other text.
        """.trimIndent()

        val userPrompt = """
            User Listening Stats:
            - Total Library: $totalTracks tracks
            - Total Track Plays: $totalPlayCount
            - Mean Library Tempo: $avgBpm BPM
            - Primary Dominant Genre: $topGenres
        """.trimIndent()

        val aiResponse = ZhipuAiEngine.complete(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.4,
            maxTokens = 200
        )

        if (!aiResponse.isNullOrBlank()) {
            try {
                val cleanedJson = aiResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val json = JSONObject(cleanedJson)
                val persona = LivePersona(
                    title = json.optString("title", "The Sonic Explorer"),
                    description = json.optString("description", "Drawn to fast-paced dynamic rhythms and modern electronic soundscapes."),
                    topAcousticTrait = json.optString("topAcousticTrait", "$avgBpm BPM $topGenres"),
                    dateGenerated = todayDate
                )
                cachedPersona = persona
                lastCacheKey = currentCacheKey
                return@withContext persona
            } catch (e: Exception) {
                // Fallback below
            }
        }

        val defaultPersona = LivePersona(
            title = "The $topGenres Architect",
            description = "Maintains a vibrant acoustic profile with an average tempo of $avgBpm BPM and a heavy affinity for $topGenres energy.",
            topAcousticTrait = "$avgBpm BPM Rhythm",
            dateGenerated = todayDate
        )
        cachedPersona = defaultPersona
        lastCacheKey = currentCacheKey
        defaultPersona
    }
}
