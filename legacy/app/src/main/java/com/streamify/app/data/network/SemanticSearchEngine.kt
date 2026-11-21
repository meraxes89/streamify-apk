package com.streamify.app.data.network

import android.util.LruCache
import com.streamify.app.viewmodel.OnlineSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Fuzzy Semantic & Mood Search Engine
 * Converts natural language vibes, descriptions, and acoustic memories into exact
 * canonical tracks using GLM-4-Flash, racing them in parallel across YouTube Innertube.
 */
object SemanticSearchEngine {

    private val semanticCache = LruCache<String, List<OnlineSearchResult>>(64)

    fun isSemanticQuery(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.length < 5) return false
        val vibeKeywords = listOf(
            "vibe", "mood", "drive", "rain", "workout", "gym", "sad", "happy",
            "chill", "study", "lofi", "lo-fi", "sleep", "dark", "night", "epic",
            "anime", "soundtrack", "instrumental", "aesthetic", "party", "energy",
            "aggressive", "calm", "relax", "melancholy", "nostalgic", "summer",
            "synth", "phonk", "bass", "gaming", "slowed", "reverb"
        )
        val wordCount = q.split("\\s+".toRegex()).size
        return (wordCount >= 3) || vibeKeywords.any { q.contains(it) }
    }

    suspend fun resolveMoodQuery(query: String): List<OnlineSearchResult> = withContext(Dispatchers.IO) {
        val cleanQ = query.trim()
        val cached = semanticCache.get(cleanQ.lowercase())
        if (cached != null) return@withContext cached

        val systemPrompt = """
            You are a music metadata API. Convert the user's vibe, scene, or natural language query into 5 exact canonical tracks.
            Return ONLY a JSON array of objects with "title" and "artist".
            Example: [{"title": "Midnight City", "artist": "M83"}, {"title": "Starboy", "artist": "The Weeknd"}]
            No markdown formatting, no commentary. Just raw JSON.
        """.trimIndent()

        val aiResponse = ZhipuAiEngine.complete(
            systemPrompt = systemPrompt,
            userPrompt = cleanQ,
            temperature = 0.3,
            maxTokens = 256
        ) ?: return@withContext emptyList()

        val cleanedJson = aiResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val resolvedList = coroutineScope {
            try {
                val jsonArr = JSONArray(cleanedJson)
                val deferredList = (0 until jsonArr.length().coerceAtMost(5)).map { i ->
                    async(Dispatchers.IO) {
                        try {
                            val obj = jsonArr.getJSONObject(i)
                            val title = obj.optString("title", "")
                            val artist = obj.optString("artist", "")
                            if (title.isNotBlank()) {
                                val searchQ = "$title $artist".trim()
                                val results = YouTubeMusicSearchApi.search(searchQ, maxResults = 1)
                                results.firstOrNull()
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                deferredList.awaitAll().filterNotNull()
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (resolvedList.isNotEmpty()) {
            semanticCache.put(cleanQ.lowercase(), resolvedList)
        }
        resolvedList
    }
}
