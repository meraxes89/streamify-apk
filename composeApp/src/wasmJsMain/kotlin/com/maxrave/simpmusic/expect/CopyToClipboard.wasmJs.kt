package com.maxrave.simpmusic.expect

import kotlinx.browser.window

actual fun copyToClipboard(
    label: String,
    text: String,
) {
    runCatching {
        window.navigator.clipboard.writeText(text)
    }
}
