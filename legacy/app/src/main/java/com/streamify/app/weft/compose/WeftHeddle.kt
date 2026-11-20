// ─────────────────────────────────────────────────────────────────────────────
// VENDORED into Streamify from zephyr4289/Weft @ 08e3a53
// (android/weft-compose/src/main/kotlin/dev/weft/compose/WeftHeddle.kt) per
// the Engineering Directive: Real-Device Integration of the Weft
// Continuous-State Plane.
//
// Kernel semantics are FROZEN — only the package declaration + the
// dev.weft imports changed.
// ─────────────────────────────────────────────────────────────────────────────

// WeftHeddle.kt — Jetpack Compose Heddle binding for Weft
//
// WHY EXISTS: Implements the Compose draw-phase deferred read pattern and
// ViewModel-scoped Steward binding per WHITEPAPER §8.1 and DIRECTIVE-12 T12.3.
//
// DISPOSAL ORDERING:
// The Heddle is remembered and tied to the ViewModel lifecycle. On recomposition,
// the Heddle instance is preserved (survives recomposition); disposal occurs
// strictly when the enclosing scope exits via DisposableEffect or ViewModel onCleared.

package com.streamify.app.weft.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.weft.Steward
import com.streamify.app.weft.Weft

/**
 * Lifecycle reattachment policy seam per RFC Q5 (design-only stub).
 */
public enum class ReattachPolicy {
    RESET_ON_ATTACH,
    PRESERVE_HELD,
    REVOKE_AND_RENEW
}

/**
 * A Compose Heddle holding a Weft channel bound to a Steward.
 *
 * Disposal implements the [ReattachPolicy] semantics declared at construction
 * (previously the enum existed but dispose() ignored it):
 *
 *  - [ReattachPolicy.PRESERVE_HELD] — the Weft stays bound to the ViewModel-
 *    scoped Steward; re-entering the composition re-binds to the same channel
 *    and the last claimed frame is still held (default; survives navigation).
 *  - [ReattachPolicy.RESET_ON_ATTACH] — the channel is kept but the next
 *    claim() after re-attach re-renders from whatever is freshest at that
 *    moment; no writer-side action on dispose (the producer keeps running).
 *  - [ReattachPolicy.REVOKE_AND_RENEW] — the Weft is revoked and released
 *    back to the Steward on dispose (I6 ordering: revoke BEFORE the reference
 *    is dropped, so a late publish is a DROPPED_REVOKED no-op, never a write
 *    into a channel nobody owns). Re-entering the composition allocates a
 *    fresh channel.
 */
public class WeftHeddle(
    public val steward: Steward,
    public val weft: Weft,
    public val policy: ReattachPolicy = ReattachPolicy.PRESERVE_HELD
) {
    public var isDisposed: Boolean = false
        private set

    internal fun dispose() {
        if (isDisposed) return
        isDisposed = true
        if (policy == ReattachPolicy.REVOKE_AND_RENEW) {
            steward.release(weft)
        }
    }
}

/**
 * Remember a [WeftHeddle] bound to a ViewModel-scoped [Steward].
 *
 * Survives recomposition cycles; disposed when the calling composable leaves the composition.
 *
 * @param capacity Buffer capacity in elements.
 * @param policy Lifecycle reattachment policy.
 */
@Composable
public fun rememberWeftHeddle(
    capacity: Int = 1024,
    policy: ReattachPolicy = ReattachPolicy.PRESERVE_HELD
): WeftHeddle {
    val steward: Steward = viewModel()
    return rememberWeftHeddle(capacity, policy, steward)
}

/**
 * Remember a [WeftHeddle] bound to a provided [Steward].
 *
 * @param capacity Buffer capacity in elements.
 * @param policy Lifecycle reattachment policy.
 * @param steward The Steward instance managing the channel.
 */
@Composable
public fun rememberWeftHeddle(
    capacity: Int,
    policy: ReattachPolicy,
    steward: Steward
): WeftHeddle {
    val heddle = remember(steward, capacity) {
        val weft = steward.weft<ByteArray>(capacity)
        WeftHeddle(steward, weft, policy)
    }

    DisposableEffect(heddle) {
        onDispose {
            heddle.dispose()
        }
    }

    return heddle
}
