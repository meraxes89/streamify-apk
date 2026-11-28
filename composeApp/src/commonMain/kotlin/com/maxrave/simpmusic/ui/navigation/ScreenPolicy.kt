package com.maxrave.simpmusic.ui.navigation

import com.maxrave.simpmusic.ui.navigation.destination.home.AdminDashboardDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AnalyticsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AnnouncementInboxDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.AppLogDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.CreditDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.HomeDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.ListenTogetherSettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.MoodDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.NotificationDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.SettingsDestination
import com.maxrave.simpmusic.ui.navigation.destination.home.WrappedDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.LibraryDynamicPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.library.MixForYouDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.BrowseDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.LocalPlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.MoreAlbumsDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PlaylistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.DiscordLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LastfmLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.LoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.login.SpotifyLoginDestination
import com.maxrave.simpmusic.ui.navigation.destination.player.FullscreenDestination
import com.maxrave.simpmusic.ui.navigation.destination.search.SearchDestination
import kotlin.reflect.KClass

/**
 * The transition vocabulary of RFC-003 §3.1.
 *
 * Every destination resolves to one of four kinds, and the NavHost resolves the four
 * enter/exit lambdas from the PAIR of (initial, target) policies — see
 * [resolveTransitions]. All kinds are expressible with graphics-layer-only effects
 * (alpha + translationX/Y + scale), so a navigation never triggers composition or
 * layout work mid-flight.
 */
enum class TransitionKind {
    /** ±25% horizontal drift + crossfade. Peer and drill-down motion. */
    SharedAxisX,

    /** 0.85f ↔ 1.15f scale + crossfade. Depth / modal motion. */
    SharedAxisZ,

    /** Pure crossfade. Top-level tab switches. */
    Fade,

    /** Bottom-up sheet-like motion. The fullscreen player and the Wrapped reel. */
    SlideVertical,
}

/**
 * What the shell chrome (bottom bar / rail / sidebar, mini player) does while a
 * destination is CURRENT — derived reactively in App.kt from
 * `currentBackStackEntryAsState()`, never set imperatively by screens.
 */
data class ChromePolicy(
    val showNavBar: Boolean = true,
    val showMiniPlayer: Boolean = true,
    /** Destinations that draw on their own dark ground whatever the user's theme is. */
    val forceDarkContent: Boolean = false,
    /** Full-bleed takeovers that own the whole window edge to edge. */
    val immersive: Boolean = false,
)

/** Per-destination routing policy: how it moves, and what chrome it wants. */
data class ScreenPolicy(
    val transition: TransitionKind = TransitionKind.SharedAxisX,
    val chrome: ChromePolicy = ChromePolicy(),
)

/**
 * The registry: one policy for every destination across AppNavigationGraph,
 * HomeScreenGraph, LibraryScreenGraph, ListScreenGraph and LoginScreenGraph.
 *
 * Pure Kotlin, no Compose dependency, so the exhaustive coverage test can enumerate
 * every `*Destination` type and fail the build when a new destination is added
 * without a policy.
 */
object ScreenPolicyRegistry {
    /** Chrome hidden + immersive: full-bleed takeovers. */
    private val IMMERSIVE_DARK =
        ScreenPolicy(
            transition = TransitionKind.SlideVertical,
            chrome = ChromePolicy(showNavBar = false, showMiniPlayer = false, forceDarkContent = true, immersive = true),
        )

    /** Login WebViews: chrome hidden (pushed from Settings/Player over content). */
    private val LOGIN =
        ScreenPolicy(
            transition = TransitionKind.SharedAxisZ,
            chrome = ChromePolicy(showNavBar = false, showMiniPlayer = false),
        )

    /** Top-level tabs the bottom bar/rail points at. */
    private val TAB = ScreenPolicy(transition = TransitionKind.Fade)

    /** Drill-down pages reached from a card or a list row. */
    private val DETAIL = ScreenPolicy(transition = TransitionKind.SharedAxisX)

    /** Secondary pages pushed on top of a tab. */
    private val PUSH = ScreenPolicy(transition = TransitionKind.SharedAxisX)

    /** Depth-style pushes for pages that open like a modal over their parent. */
    private val MODAL_PUSH = ScreenPolicy(transition = TransitionKind.SharedAxisZ)

    private val POLICIES: Map<KClass<*>, ScreenPolicy> = buildMap {
        // ── Tabs (AppNavigationGraph) ────────────────────────────────────────────
        put(HomeDestination::class, TAB)
        put(SearchDestination::class, TAB)
        put(LibraryDestination::class, TAB)
        put(MixForYouDestination::class, TAB)
        put(AnalyticsDestination::class, TAB)

        // ── Takeovers (AppNavigationGraph) ───────────────────────────────────────
        put(FullscreenDestination::class, IMMERSIVE_DARK)
        put(WrappedDestination::class, IMMERSIVE_DARK)

        // ── HomeScreenGraph ──────────────────────────────────────────────────────
        put(SettingsDestination::class, PUSH)
        put(NotificationDestination::class, PUSH)
        put(CreditDestination::class, PUSH)
        put(AppLogDestination::class, PUSH)
        put(AdminDashboardDestination::class, MODAL_PUSH)
        put(AnnouncementInboxDestination::class, MODAL_PUSH)
        put(MoodDestination::class, PUSH)
        put(ListenTogetherDestination::class, MODAL_PUSH)
        put(ListenTogetherSettingsDestination::class, PUSH)

        // ── LibraryScreenGraph ───────────────────────────────────────────────────
        put(LibraryDynamicPlaylistDestination::class, DETAIL)

        // ── ListScreenGraph ──────────────────────────────────────────────────────
        put(AlbumDestination::class, DETAIL)
        put(ArtistDestination::class, DETAIL)
        put(PlaylistDestination::class, DETAIL)
        put(LocalPlaylistDestination::class, DETAIL)
        put(PodcastDestination::class, DETAIL)
        put(BrowseDestination::class, DETAIL)
        put(MoreAlbumsDestination::class, DETAIL)

        // ── LoginScreenGraph ─────────────────────────────────────────────────────
        put(LoginDestination::class, LOGIN)
        put(SpotifyLoginDestination::class, LOGIN)
        put(DiscordLoginDestination::class, LOGIN)
        put(LastfmLoginDestination::class, LOGIN)
    }

    /**
     * The policy for a destination class. Unknown destinations (including null, before
     * the graph settles on splash) get the drill-down default: a SharedAxisX push with
     * the full chrome — the safest shape, matching what the old global NavHost
     * transition did for every route.
     */
    fun policyFor(destination: KClass<*>?): ScreenPolicy = destination?.let { POLICIES[it] } ?: DETAIL

    /** Every registered destination class — used by the exhaustive coverage test. */
    val registeredDestinations: Set<KClass<*>> = POLICIES.keys
}

/**
 * Resolves the transition to run for a navigation from [initial] to [target].
 *
 * The TARGET's policy owns the axis: entering Wrapped from Analytics slides up
 * (Wrapped is SlideVertical), and popping back slides down — the pop lambdas are
 * built from the SAME policy, mirrored, so backtracking retraces the incoming
 * motion instead of playing a different one.
 */
object TransitionResolver {
    fun resolve(initial: ScreenPolicy?, target: ScreenPolicy): ResolvedTransition =
        ResolvedTransition(target.transition)
}

/** The single transition a navigation actually plays. */
data class ResolvedTransition(val kind: TransitionKind)
