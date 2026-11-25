package com.streamify.media.hal

import android.content.Context

/**
 * Entry point for Android media capability detection (spatial / Dolby AudioManager probing).
 */
object StreamifyMedia3Module {

    fun init(context: Context) {
        DolbySpatialManager.init(context)
    }
}
