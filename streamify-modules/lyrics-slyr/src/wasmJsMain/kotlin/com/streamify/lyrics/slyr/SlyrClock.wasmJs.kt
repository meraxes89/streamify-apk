package com.streamify.lyrics.slyr

import kotlinx.browser.window

internal actual fun getMonotonicTimeNs(): Long {
    val performanceNowMs = window.performance.now()
    return (performanceNowMs * 1_000_000.0).toLong()
}
