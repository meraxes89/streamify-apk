package com.maxrave.simpmusic.expect

import kotlinx.browser.window

actual fun currentOrientation(): Orientation =
    if (window.innerWidth >= window.innerHeight) {
        Orientation.LANDSCAPE
    } else {
        Orientation.PORTRAIT
    }
