package com.maxrave.simpmusic.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.maxrave.simpmusic.ui.theme.ForceDarkContent
import kotlin.reflect.KClass

/**
 * RFC-003 §3.4 composition locals.
 *
 * `SharedTransitionLayout` is provided ONCE around the one NavHost by App.kt, and
 * every destination's content runs inside an [AnimatedVisibilityScope] created by
 * navigation itself. Rather than every hero call site capturing both, they are
 * provided here: the scope by [appComposable], the shared-transition scope by the
 * root container. Both default to null, and `Modifier.sharedHero` (SharedHero.kt)
 * no-ops when either is missing — that is the whole "safely detached" contract.
 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScopeHolder?> { null }

/**
 * The narrow slice of `SharedTransitionScope` handed to heroes. A holder rather
 * than the real type so this file owns no experimental annotations; App.kt wraps
 * the real scope, and `Modifier.sharedHero` (SharedHero.kt) is the only consumer.
 */
interface SharedTransitionScopeHolder {
    /** Runs [block] against the real SharedTransitionScope's sharedElement modifier. */
    fun sharedElement(
        modifier: Modifier,
        key: Any,
        animatedVisibilityScope: AnimatedVisibilityScope,
    ): Modifier
}

val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The deceleration curve of every programmatic transition (RFC-003 §3.1).
 * NavHost swaps in linear seeking by itself during Android predictive back.
 */
val SpatialEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

private const val TRANSITION_MS = 350

/**
 * Builds the enter/exit pair for a [TransitionKind].
 *
 * Every effect is a graphicsLayer property (alpha, translation, scale): the
 * transition runs entirely in the draw phase, so navigation never composes or
 * lays out content mid-flight. `+` composes fade with the motion so both screens
 * stay partially visible across the overlap, the shared-axis way.
 */
object SpatialTransitions {
    /** 25% drift + crossfade: both screens overlap smoothly instead of a 100% slide. */
    fun enter(kind: TransitionKind): EnterTransition = when (kind) {
        TransitionKind.SharedAxisX ->
            fadeIn(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideInHorizontally(tween(TRANSITION_MS, easing = SpatialEasing)) { it / 4 }
        TransitionKind.SharedAxisZ ->
            fadeIn(tween(TRANSITION_MS, easing = SpatialEasing)) +
                scaleIn(tween(TRANSITION_MS, easing = SpatialEasing), initialScale = 0.85f)
        TransitionKind.Fade -> fadeIn(tween(TRANSITION_MS, easing = SpatialEasing))
        TransitionKind.SlideVertical ->
            fadeIn(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideInVertically(tween(TRANSITION_MS, easing = SpatialEasing)) { it }
    }

    /** Exiting content drifts a quarter the other way, not off-screen. */
    fun exit(kind: TransitionKind): ExitTransition = when (kind) {
        TransitionKind.SharedAxisX ->
            fadeOut(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideOutHorizontally(tween(TRANSITION_MS, easing = SpatialEasing)) { -it / 4 }
        TransitionKind.SharedAxisZ ->
            fadeOut(tween(TRANSITION_MS, easing = SpatialEasing)) +
                scaleOut(tween(TRANSITION_MS, easing = SpatialEasing), targetScale = 1.15f)
        TransitionKind.Fade -> fadeOut(tween(TRANSITION_MS, easing = SpatialEasing))
        TransitionKind.SlideVertical ->
            fadeOut(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideOutVertically(tween(TRANSITION_MS, easing = SpatialEasing)) { -it / 8 }
    }

    /** The mirrored pair for pop: entering content comes from where exit put it. */
    fun popEnter(kind: TransitionKind): EnterTransition = when (kind) {
        TransitionKind.SharedAxisX ->
            fadeIn(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideInHorizontally(tween(TRANSITION_MS, easing = SpatialEasing)) { -it / 4 }
        TransitionKind.SharedAxisZ ->
            fadeIn(tween(TRANSITION_MS, easing = SpatialEasing)) +
                scaleIn(tween(TRANSITION_MS, easing = SpatialEasing), initialScale = 1.15f)
        TransitionKind.Fade -> fadeIn(tween(TRANSITION_MS, easing = SpatialEasing))
        TransitionKind.SlideVertical ->
            fadeIn(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideInVertically(tween(TRANSITION_MS, easing = SpatialEasing)) { -it / 8 }
    }

    fun popExit(kind: TransitionKind): ExitTransition = when (kind) {
        TransitionKind.SharedAxisX ->
            fadeOut(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideOutHorizontally(tween(TRANSITION_MS, easing = SpatialEasing)) { it / 4 }
        TransitionKind.SharedAxisZ ->
            fadeOut(tween(TRANSITION_MS, easing = SpatialEasing)) +
                scaleOut(tween(TRANSITION_MS, easing = SpatialEasing), targetScale = 0.85f)
        TransitionKind.Fade -> fadeOut(tween(TRANSITION_MS, easing = SpatialEasing))
        TransitionKind.SlideVertical ->
            fadeOut(tween(TRANSITION_MS, easing = SpatialEasing)) +
                slideOutVertically(tween(TRANSITION_MS, easing = SpatialEasing)) { it }
    }
}

/** The transition the app had before RFC-003: the full-width slide. Kept for beta-off. */
object LegacyTransitions {
    fun enter(): EnterTransition = fadeIn() + slideInHorizontally { it }
    fun exit(): ExitTransition = fadeOut() + slideOutHorizontally { -it }
    fun popEnter(): EnterTransition = fadeIn() + slideInHorizontally { -it }
    fun popExit(): ExitTransition = fadeOut() + slideOutHorizontally { it }
}

/**
 * The single destination builder used by all four graphs.
 *
 * Replaces bare `composable<T>` everywhere so every destination, uniformly:
 *  - provides [LocalNavAnimatedVisibilityScope] so hero elements inside it can
 *    morph without either side capturing navigation internals;
 *  - wraps `forceDarkContent` destinations in [ForceDarkContent] (moved here from
 *    the two call sites that did it by hand);
 *  - resolves its transition from the [ScreenPolicyRegistry] when the beta is on,
 *    and keeps the legacy full-width slide when it is off.
 *
 * [spatialEnabled] arrives as a parameter, not a CompositionLocal: the NavHost
 * builder lambda is not a composable context, and the flag changing must rebuild
 * only these transition lambdas — the NavHost tree, the graph and the back stack
 * are identical either way, which is the zero-breakage rollout contract.
 */
inline fun <reified T : Any> NavGraphBuilder.appComposable(
    spatialEnabled: Boolean,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    val policy = ScreenPolicyRegistry.policyFor(T::class)
    composable<T>(
        enterTransition = {
            if (spatialEnabled) SpatialTransitions.enter(policy.transition) else LegacyTransitions.enter()
        },
        exitTransition = {
            if (spatialEnabled) SpatialTransitions.exit(policy.transition) else LegacyTransitions.exit()
        },
        popEnterTransition = {
            if (spatialEnabled) SpatialTransitions.popEnter(policy.transition) else LegacyTransitions.popEnter()
        },
        popExitTransition = {
            if (spatialEnabled) SpatialTransitions.popExit(policy.transition) else LegacyTransitions.popExit()
        },
    ) { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
            if (policy.chrome.forceDarkContent) {
                ForceDarkContent { content(entry) }
            } else {
                content(entry)
            }
        }
    }
}
