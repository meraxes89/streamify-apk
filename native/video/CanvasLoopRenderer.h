#ifndef STREAMIFY_CANVAS_LOOP_RENDERER_H
#define STREAMIFY_CANVAS_LOOP_RENDERER_H
// ============================================================================
//  CanvasLoopRenderer.h — per-track Now-Playing Canvas loop renderer
//  (Phase 3, BEHIND.md #45)
// ============================================================================
//
//  Owns the loop clock for one Canvas surface and turns wall-clock samples
//  into:
//    * a CanvasLoopFrame (phase/crossfade + breathing transform), and
//    * a 2x3 affine matrix for the Compose canvas (scale -> rotate ->
//      translate composed in draw order), exactly the 6-float layout
//      androidx.compose.ui.graphics.Matrix values use:
//          [ scaleX  skewX   transX
//            skewY   scaleY  transY ]
//
//  Clock discipline: the renderer never reads the OS clock itself. The
//  caller supplies monotonic milliseconds (frame callbacks, Choreographer,
//  or test clocks), and the renderer maps them onto LOOP-RELATIVE time via
//  an origin captured at reset(). pause()/resume() shift the origin so the
//  loop freezes and continues without a phase jump; seek() re-anchors for
//  track changes. All state is plain floats — no allocation on any path.
//
//  Threading: one renderer per surface; advanceTo() is not internally
//  synchronized (UI-thread discipline, same as the Choreographer contract).
// ============================================================================

#include "CanvasLoopMath.h"

#include <cstdint>

namespace streamify::video {

// Motion amplitudes (tuned for a subtle Spotify-Canvas feel; all periodic).
struct CanvasLoopMotion {
    float scaleAmp = 0.012f;       // +-1.2% breathing
    float rotateAmpRad = 0.035f;   // +-2 degrees drift
    float translateAmp = 0.015f;   // +-1.5% parallax (fraction of canvas)
    float hueTurns = 1.0f;         // one full hue revolution per loop
};

class CanvasLoopRenderer {
public:
    CanvasLoopRenderer() = default;

    // Re-anchor the loop at `monotonicMs` (track change / first frame).
    void reset(int64_t monotonicMs, const CanvasLoopConfig& cfg,
               const CanvasLoopMotion& motion);

    // Freeze the loop at the current phase (idempotent while paused).
    void pause(int64_t monotonicMs);

    // Continue from the frozen phase (no-op while live).
    void resume(int64_t monotonicMs);

    // Jump the loop origin (keeps config/motion) — e.g. scrubbing.
    void seek(int64_t monotonicMs, float phase01);

    // Produce the frame valid at `monotonicMs`. Zero allocation; safe to
    // call from the UI frame callback. Falls back to the frame at the
    // pause point while paused.
    void advanceTo(int64_t monotonicMs, CanvasLoopFrame* outFrame);

    // Compose the frame's breathing transform into a 2x3 affine matrix
    // (values array order: scaleX, skewX, transX, skewY, scaleY, transY).
    // `translateScalePx` converts the unit translate amplitude to pixels
    // (typically min(canvasW, canvasH)).
    static void composeCanvasMatrix(const CanvasLoopFrame& frame,
                                    float translateAmp, float translateScalePx,
                                    float out6[6]);

    // --- introspection (tests / debug overlays) ---
    const CanvasLoopConfig& config() const { return cfg_; }
    const CanvasLoopMotion& motion() const { return motion_; }
    bool paused() const { return paused_; }
    float lastPhase() const { return lastPhase_; }

private:
    // Loop-time origin: frameTime = monotonicMs - originMs_ (+ pause shift).
    int64_t originMs_ = 0;
    int64_t pausedAtMs_ = 0;
    bool paused_ = false;
    bool everReset_ = false;
    CanvasLoopConfig cfg_{};
    CanvasLoopMotion motion_{};
    float lastPhase_ = 0.0f;
};

}  // namespace streamify::video

#endif  // STREAMIFY_CANVAS_LOOP_RENDERER_H
