package com.maxrave.simpmusic.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.getPlatform

/**
 * Central, deterministic shared-element keys (RFC-003 §3.4).
 *
 * A hero key must be identical on both sides of a morph AND unique across the
 * whole composition, because the same item can be on screen twice at once — a
 * playlist in two carousels, or the mini player while its own detail page slides
 * in. Every key therefore carries an [Origin] that names the surface it is
 * mounted on, plus the remote id of the item it marks.
 *
 * Use [SharedKeys.hero] (never string concatenation) so the shape of every key is
 * the same and the coverage test can enumerate the origins.
 */
object SharedKeys {
    /** The surface a hero is mounted on — the disambiguator of every key. */
    enum class Origin {
        /** The MiniPlayer's artwork, in any of its three mounts (phone bar, tablet rail pad, desktop capsule). */
        MINI_PLAYER,

        /** The Now Playing sheet's centered artwork — and the MiniPlayer's, which morphs into it. */
        NOW_PLAYING_ARTWORK,

        /** Detail header artwork, disambiguated by kind so two pages can hold the same remote id. */
        ALBUM,
        PLAYLIST,
        PODCAST,
        ARTIST,
        LOCAL_PLAYLIST,

        /** The Wrapped entry card (Analytics) and the reel it opens. */
        WRAPPED,

        /** The Search screen's floating search bar. */
        SEARCH_BAR,

        /** The Analytics screen's Wrapped entry card. */
        ANALYTICS_WRAPPED,

        /** The Wrapped reel's opening card. */
        WRAPPED_REEL,

        /** A Home/library grid or carousel card. */
        CARD,

        /** A detail page's header artwork (album, artist, playlist, podcast, browse, more-albums, local playlist). */
        DETAIL_HEADER,

        /** A Moods & Genres category tile (Search and Mood screens). */
        MOOD_TILE,
    }

    /**
     * Builds one hero key. [origin] disambiguates, [id] is the remote/local id of
     * the thing itself (browseId, playlistId, videoId, podcastId, mood params).
     */
    fun hero(origin: Origin, id: String): String = "hero/${origin.name}/$id"
}

/**
 * `Modifier.sharedHero(key)` opts an element into bounds morphing across a
 * navigation transition, or across the MiniPlayer ↔ Now Playing expand.
 *
 * Safe by construction, in three layers:
 *  1. No [LocalSharedTransitionScope] (beta off, Tier 4 software raster, reduced
 *     motion) → plain `this`, zero cost: the lookups are two CompositionLocal
 *     reads that return before any modifier is built.
 *  2. No [LocalNavAnimatedVisibilityScope] (the element is outside the NavHost —
 *     the MiniPlayer mounts in the Scaffold, not in a destination) → same no-op.
 *  3. Both present → the scope's `sharedElement` runs with the key.
 *
 * The modifier is NOT `@Composable`, but it reads two CompositionLocals, so it is
 * one: call sites use it inside a composable's modifier chain exactly like
 * `Modifier.hoverZoom`, which is the established pattern in this codebase.
 */
@Composable
fun Modifier.sharedHero(key: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val navScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return sharedScope.sharedElement(this, key, navScope)
}

/**
 * The gate every hero and the SharedTransitionLayout provision go through.
 *
 * Tier 4 (RFC-001 §3: LLVMpipe, SwiftShader) rasterizes on the CPU; shared
 * element morphing over it means readbacks on every frame of the transition, and
 * the hero becomes a stutter. Reduced-motion users get the same answer: the
 * morph is exactly the kind of large-canvas motion they asked the app to drop.
 *
 * Deliberately conservative and synchronous: a capability check that could flip
 * mid-session would make hero wiring appear and disappear between frames. The
 * software-rasterizer signals are resolvable once at composition.
 */
object SpatialCapability {
    /**
     * Whether shared-element morphing may run. [betaEnabled] is the settings
     * toggle; the platform check excludes known software rasterizers.
     */
    fun heroesAllowed(betaEnabled: Boolean, reduceMotion: Boolean): Boolean =
        betaEnabled && !reduceMotion && !isSoftwareRasterizer()

    /** True on platforms whose Compose backend is a software/CPU rasterizer. */
    fun isSoftwareRasterizer(): Boolean = when (getPlatform()) {
        // The browser target falls back to SwiftShader whenever WebGL is missing
        // (headless CI, locked-down VMs): treated as Tier 4 rather than detected
        // per-run, because a WebGL context probe here would be async state.
        Platform.Web -> true
        // Android, Desktop and iOS run hardware rasterizers; their low tiers are
        // handled by the reduced-motion and liquid-glass gating, not this.
        Platform.Android, Platform.Desktop, Platform.iOS -> false
    }
}
