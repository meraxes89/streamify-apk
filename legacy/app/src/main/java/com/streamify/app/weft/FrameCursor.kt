// ─────────────────────────────────────────────────────────────────────────────
// VENDORED into Streamify from zephyr4289/Weft @ 08e3a53
// (android/weft-core/src/main/kotlin/dev/weft/FrameCursor.kt) per the
// Engineering Directive: Real-Device Integration of the Weft
// Continuous-State Plane.
//
// Kernel semantics are FROZEN — only the package declaration changed.
// ─────────────────────────────────────────────────────────────────────────────

// FrameCursor.kt — per-reader freshness telemetry (driver layer)
//
// WHY EXISTS: RFC-0008. The Triad Protocol drops intermediate frames by
// design (latest-wins IS the semantics of display) — but "dropped" was
// invisible to the reader. The kernel envelope already carries the fix:
// `seq` increments once per publish, so any reader can compute exactly how
// many frames were published between its own claims and never seen:
//
//     framesBehind = seq_now - seq_prev - 1
//
// ZERO kernel changes: no second atomic, no protocol version bump (the
// kernel surface stays frozen — 02 §2.2). The reader diffs its own claimed
// envelope seqs. Draw code can use this as a control signal: adapt detail
// level (LOD), skip decorative work, or flag degradation when it happens.
//
// STATUS: SOURCE-ONLY, PENDING REAL-DEVICE VERIFICATION.

package com.streamify.app.weft

import java.nio.ByteBuffer

/// One claim's freshness report.
class FrameClaim(
    /// Envelope seq of the claimed frame (the frame the reader now holds).
    val seq: Int,
    /// Frames published between the previous claim and this one that this
    /// reader never saw. 0 on the first claim (the null frame is the
    /// baseline: seq 0). A decreasing seq is treated as a writer reset —
    /// no drop accounting across the reset.
    val framesBehind: Int,
    /// True on the first claim of this cursor (no prior baseline).
    val first: Boolean,
    /// Zero-copy live payload view of the reader-held buffer — exclusively
    /// the reader's until its next claim (RFC-0001 §4.3). Absolute index 0
    /// = payload start (rLiveBuf slice semantics).
    val buf: ByteBuffer,
)

class FrameCursor {
    private var lastSeq: Int = 0
    private var hasClaimed: Boolean = false

    /// Accumulated dropped frames across the cursor's lifetime (advisory).
    var totalDropped: Int = 0
        private set

    /// Number of claims made through this cursor.
    var claims: Int = 0
        private set

    /// Claim the freshest frame and report freshness. Draw-phase hot path:
    /// one exchange, one envelope read, integer math. Wait-free (I3).
    fun claim(weft: Weft): FrameClaim {
        weft.claim()
        val seq = weft.rSeq()
        val first = !hasClaimed
        var framesBehind = 0
        if (hasClaimed && seq > lastSeq) {
            framesBehind = seq - lastSeq - 1
        }
        hasClaimed = true
        totalDropped += framesBehind
        lastSeq = seq
        claims++
        return FrameClaim(seq, framesBehind, first, weft.rLiveBuf())
    }
}
