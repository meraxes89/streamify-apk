package com.streamify.app.media.voice

/**
 * MediaSessionVoiceHandler (Gap #56) — Google Assistant voice action
 * resolution.
 *
 * Pure, deterministic matching over ACTION_PLAY_FROM_SEARCH-style queries
 * ("Play my Daylist on Streamify", "shuffle my likes", "play Focus Flow
 * playlist", "join the jam"). The session callback hands the parsed
 * [VoiceCommand] to the app's playback pipelines; the JVM suite locks
 * every rule and its precedence.
 */

/** Resolved intent of one Assistant voice query. */
sealed class VoiceCommand {
    /** "Play my Daylist" / "play my daily mix". */
    object PlayDaylist : VoiceCommand() {
        private fun readResolve(): Any = PlayDaylist
    }

    /** "Shuffle my likes" / "play my favorites". */
    object PlayLikedShuffle : VoiceCommand() {
        private fun readResolve(): Any = PlayLikedShuffle
    }

    /** "Play <name> playlist" — [nameQuery] is the bare playlist name. */
    data class PlayPlaylist(val nameQuery: String) : VoiceCommand()

    /** Generic "play <query>" / "shuffle <query>" free-text search. */
    data class PlaySearch(val query: String, val shuffle: Boolean) : VoiceCommand()

    /** "Join the jam" (optionally carrying a room code word). */
    data class JoinJam(val roomCode: String? = null) : VoiceCommand()
}

object MediaSessionVoiceHandler {

    private val LIKES_SYNONYMS = listOf("liked songs", "liked", "likes", "favorites", "favourites", "my songs")
    private val DAYLIST_SYNONYMS = listOf("daylist", "daily mix", "day list", "my mix")
    private val FILLER_TOKENS = setOf(
        "playlist", "shuffle", "play", "my", "the", "a", "on", "in",
        "streamify", "spotify", "please"
    )

    /**
     * Resolve one spoken query. Precedence (first match wins):
     *  1. daylist / daily-mix intents
     *  2. liked-songs shuffle intents
     *  3. jam join intents
     *  4. "<name> playlist" intents
     *  5. free-text search (shuffle flag honored)
     * Null query / blank / pure filler words → null (nothing to play).
     */
    fun resolve(query: String?): VoiceCommand? {
        val text = query?.trim()?.lowercase() ?: return null
        if (text.isEmpty()) return null

        if (DAYLIST_SYNONYMS.any { text.contains(it) }) return VoiceCommand.PlayDaylist

        if (LIKES_SYNONYMS.any { text.contains(it) }) return VoiceCommand.PlayLikedShuffle

        if (text.contains("jam")) {
            if (text.contains("join") || text.contains("listen along")) {
                return VoiceCommand.JoinJam(roomCode = roomCodeAfterJam(text))
            }
        }

        playlistNameOf(text)?.let { return VoiceCommand.PlayPlaylist(it) }

        val searchQuery = searchQueryOf(text)
        return when {
            searchQuery.length >= MIN_QUERY_LENGTH ->
                VoiceCommand.PlaySearch(searchQuery, shuffle = text.contains("shuffle"))
            text.startsWith("shuffle") ->
                // Bare "shuffle" = shuffle the whole library.
                VoiceCommand.PlaySearch("", shuffle = true)
            else -> null
        }
    }

    /** "play my focus flow playlist" → "focus flow" (filler tokens dropped). */
    private fun playlistNameOf(text: String): String? {
        if (!text.contains("playlist")) return null
        val name = text.split(Regex("\\s+"))
            .filter { token -> token.isNotBlank() && token !in FILLER_TOKENS }
            .joinToString(" ")
        return name.takeIf { it.length >= MIN_NAME_LENGTH }
    }

    /** Strips leading verbs + app suffixes so the search pipeline gets a clean query. */
    private fun searchQueryOf(text: String): String {
        var query = text
        for (lead in listOf("please play", "play", "shuffle", "listen to", "stream")) {
            if (query.startsWith(lead)) {
                query = query.removePrefix(lead).trim()
                break
            }
        }
        query = query.removeSuffix("on streamify").removeSuffix("in streamify").trim()
        return query
    }

    /** "join the jam abc123" → "abc123" (last token when code-like). */
    private fun roomCodeAfterJam(text: String): String? {
        val tail = text.substringAfter("jam", "").trim()
        val token = tail.split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: return null
        return token.takeIf { it.length in 4..12 && it.all { c -> c.isLetterOrDigit() } }
    }

    private const val MIN_NAME_LENGTH = 2
    private const val MIN_QUERY_LENGTH = 2
}
