package com.streamify.app.auto

/**
 * In-car browsing tree models (Gap #54) — 100% pure Kotlin so the JVM
 * shard can assert the exact hierarchy Android Auto renders.
 */

/** Stable media ids of the driving-safe root tree. */
object AutoIds {
    const val ROOT = "streamify_auto_root"
    const val FAVORITES = "auto_favorites"
    const val DAILY_MIXES = "auto_daily_mixes"
    const val JAM_QUICK_JOIN = "auto_jam_quick_join"
    const val DOWNLOADED = "auto_downloaded"
}

/**
 * One node of the Auto tree. Mirrors the subset of Media3 MediaMetadata the
 * car templates actually render (title, subtitle, artwork) plus the
 * browsable/playable flags — conversion to MediaItem happens only in the
 * service layer.
 */
data class AutoMediaNode(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val browsable: Boolean = false,
    val playable: Boolean = false,
    val artworkUri: String? = null,
    /** Video id (remote tracks) or file path (offline tracks) to resolve on play. */
    val playbackKey: String? = null
)

/**
 * Content sources the tree is built from. All sources are SUSPEND — the
 * service calls them on Dispatchers.IO so the binder thread never blocks
 * (repository reads + daylist generation can touch disk and network).
 * Faked synchronously in tests.
 */
interface AutoContentSource {
    /** Liked tracks (Favorites shelf). */
    suspend fun favoriteTracks(): List<AutoMediaNode>

    /** Current Daylist shelf (driving-safe "Daily Mixes"). */
    suspend fun dailyMixTracks(): List<AutoMediaNode>

    /** Active Jam room one-tap join row, or null when no live session. */
    suspend fun jamQuickJoin(): AutoMediaNode?

    /** Offline-playable downloaded tracks. */
    suspend fun downloadedTracks(): List<AutoMediaNode>
}

/** Empty source — used before the repositories hydrate. */
object EmptyAutoContentSource : AutoContentSource {
    override suspend fun favoriteTracks(): List<AutoMediaNode> = emptyList()
    override suspend fun dailyMixTracks(): List<AutoMediaNode> = emptyList()
    override suspend fun jamQuickJoin(): AutoMediaNode? = null
    override suspend fun downloadedTracks(): List<AutoMediaNode> = emptyList()
}

/**
 * AutoRootMediaTree (Gap #54) — the distraction-free browsing hierarchy
 * exposed to Android Auto / Automotive:
 *
 * ```
 * root
 * ├── Favorites        (playable liked tracks)
 * ├── Daily Mixes      (current Daylist shelf)
 * ├── Jam — Quick Join (one row when a Jam session is live)
 * └── Downloaded       (offline tracks, always drivable — no network)
 * ```
 *
 * Safety rules baked in:
 *  • Every browse level is capped at [MAX_CHILDREN] rows so a glance never
 *    becomes a scroll session; stable truncation keeps order deterministic.
 *  • Building a level only ever allocates value lists — no framework types,
 *    no blocking calls (sources are suspend by contract).
 */
class AutoRootMediaTree(private val source: AutoContentSource = EmptyAutoContentSource) {

    fun root(): AutoMediaNode = AutoMediaNode(
        mediaId = AutoIds.ROOT,
        title = "Streamify",
        subtitle = "Your music, road-ready",
        browsable = true,
        playable = false
    )

    suspend fun itemFor(mediaId: String): AutoMediaNode? {
        if (mediaId == AutoIds.ROOT) return root()
        return childrenOf(parentIdOf(mediaId)).firstOrNull { it.mediaId == mediaId }
    }

    /**
     * Children of a browsable node. Unknown parents yield an empty list
     * (Auto renders "nothing here" instead of erroring).
     */
    suspend fun childrenOf(parentId: String): List<AutoMediaNode> {
        val children = when (parentId) {
            AutoIds.ROOT -> shelves()
            AutoIds.FAVORITES -> source.favoriteTracks().map { it.asPlayable() }
            AutoIds.DAILY_MIXES -> source.dailyMixTracks().map { it.asPlayable() }
            AutoIds.DOWNLOADED -> source.downloadedTracks().map { it.asPlayable() }
            else -> emptyList()
        }
        return children.take(MAX_CHILDREN)
    }

    /** The four root shelves, always in the same driver-muscle-memory order. */
    suspend fun shelves(): List<AutoMediaNode> {
        val shelves = mutableListOf(
            AutoMediaNode(
                mediaId = AutoIds.FAVORITES,
                title = "Favorites",
                subtitle = "Liked songs",
                browsable = true,
                playable = false
            ),
            AutoMediaNode(
                mediaId = AutoIds.DAILY_MIXES,
                title = "Daily Mixes",
                subtitle = "Made for right now",
                browsable = true,
                playable = false
            )
        )
        source.jamQuickJoin()?.let { shelves.add(it) }
        shelves.add(
            AutoMediaNode(
                mediaId = AutoIds.DOWNLOADED,
                title = "Downloaded",
                subtitle = "Offline & ready",
                browsable = true,
                playable = false
            )
        )
        return shelves
    }

    /** Node of a playable leaf → its owning shelf id ("fav-123" → Favorites). */
    fun parentIdOf(mediaId: String): String = when {
        mediaId.startsWith(FAVORITE_PREFIX) -> AutoIds.FAVORITES
        mediaId.startsWith(DAILY_MIX_PREFIX) -> AutoIds.DAILY_MIXES
        mediaId.startsWith(DOWNLOADED_PREFIX) -> AutoIds.DOWNLOADED
        mediaId.startsWith(JAM_PREFIX) -> AutoIds.ROOT
        else -> AutoIds.ROOT
    }

    private fun AutoMediaNode.asPlayable(): AutoMediaNode =
        copy(browsable = false, playable = true)

    companion object {
        /** Distraction cap per browse level. */
        const val MAX_CHILDREN = 12

        const val FAVORITE_PREFIX = "fav-"
        const val DAILY_MIX_PREFIX = "mix-"
        const val DOWNLOADED_PREFIX = "dl-"
        const val JAM_PREFIX = "jam-"

        fun favoriteId(playbackKey: String) = "$FAVORITE_PREFIX$playbackKey"
        fun dailyMixId(playbackKey: String) = "$DAILY_MIX_PREFIX$playbackKey"
        fun downloadedId(playbackKey: String) = "$DOWNLOADED_PREFIX$playbackKey"
        fun jamId(roomCode: String) = "$JAM_PREFIX$roomCode"
    }
}
