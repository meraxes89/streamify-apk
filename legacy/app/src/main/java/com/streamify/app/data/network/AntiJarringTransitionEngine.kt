package com.streamify.app.data.network

import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * Vibe-Preservation Transition Engine (The Anti-Jarring Filter)
 * Detects tempo cliffs (|BPM_A - BPM_B| >= 35) or stark acoustic shocks,
 * dynamically generating and resolving a harmonic bridge track to smooth the queue transition.
 */
object AntiJarringTransitionEngine {

    suspend fun getHarmonicBridge(currentTrack: Track, nextTrack: Track): Track? = withContext(Dispatchers.IO) {
        val bpmDiff = abs(currentTrack.bpm - nextTrack.bpm)
        val isAcousticCliff = bpmDiff >= 35.0f && currentTrack.bpm > 0 && nextTrack.bpm > 0

        // Only trigger AI bridge if there is a severe acoustic tempo jump
        if (!isAcousticCliff) return@withContext null

        val systemPrompt = """
            You are a master harmonic mixing engine. The user is transitioning between two contrasting songs.
            Suggest ONE bridge track that smoothly transitions the tempo and mood between Track A and Track B.
            Return ONLY a JSON object with "title" and "artist".
            Example: {"title": "After Dark", "artist": "Mr.Kitty"}
            No markdown formatting, no commentary.
        """.trimIndent()

        val userPrompt = """
            Track A: ${currentTrack.title} by ${currentTrack.artist} (${currentTrack.bpm.toInt()} BPM, ${currentTrack.genre})
            Track B: ${nextTrack.title} by ${nextTrack.artist} (${nextTrack.bpm.toInt()} BPM, ${nextTrack.genre})
        """.trimIndent()

        val aiResponse = ZhipuAiEngine.complete(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.3,
            maxTokens = 128
        ) ?: return@withContext null

        try {
            val cleanedJson = aiResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleanedJson)
            val title = obj.optString("title", "")
            val artist = obj.optString("artist", "")
            if (title.isNotBlank()) {
                val results = YouTubeMusicSearchApi.search("$title $artist".trim(), maxResults = 1)
                val topMatch = results.firstOrNull()
                if (topMatch != null) {
                    return@withContext Track(
                        id = 0,
                        title = topMatch.title,
                        artist = topMatch.uploader,
                        album = "Harmonic Bridge",
                        durationSec = topMatch.duration,
                        filepath = topMatch.url,
                        coverArtPath = topMatch.thumbnail,
                        bpm = ((currentTrack.bpm + nextTrack.bpm) / 2.0f).coerceIn(60.0f, 180.0f),
                        genre = currentTrack.genre
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback null
        }
        null
    }
}
