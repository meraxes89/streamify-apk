package com.maxrave.simpmusic.expect

import kotlinx.browser.window

actual fun openUrl(url: String) {
    if (!UrlDispatchPolicy.isDispatchable(url)) return
    runCatching {
        window.open(url, "_blank")
    }
}

actual fun shareUrl(
    title: String,
    url: String,
) {
    copyToClipboard(title, url)
}
