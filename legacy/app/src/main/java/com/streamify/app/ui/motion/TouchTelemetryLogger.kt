package com.streamify.app.ui.motion

import android.view.MotionEvent
import com.streamify.app.util.SLog

/**
 * TouchTelemetryLogger — single-responsibility delegate for the activity-wide
 * touch telemetry stream (sprint P2, god-object decomposition).
 *
 * Every DOWN/UP is logged with coordinates; MOVE events are throttled to one
 * per 250 ms so drags stay visible without flooding the ring buffer. Extracted
 * verbatim from MainActivity.dispatchTouchEvent — behavior identical, the
 * Activity override is now a two-line funnel.
 */
object TouchTelemetryLogger {

    private const val MOVE_THROTTLE_MS = 250L

    @Volatile
    private var lastMoveLogMs: Long = 0L

    fun log(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                SLog.v("TOUCH", "DOWN x=${ev.x.toInt()} y=${ev.y.toInt()} pointers=${ev.pointerCount}")
            MotionEvent.ACTION_UP ->
                SLog.v("TOUCH", "UP   x=${ev.x.toInt()} y=${ev.y.toInt()}")
            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (now - lastMoveLogMs >= MOVE_THROTTLE_MS) {
                    lastMoveLogMs = now
                    SLog.v("TOUCH", "MOVE x=${ev.x.toInt()} y=${ev.y.toInt()}")
                }
            }
            MotionEvent.ACTION_CANCEL ->
                SLog.v("TOUCH", "CANCEL")
        }
    }
}
