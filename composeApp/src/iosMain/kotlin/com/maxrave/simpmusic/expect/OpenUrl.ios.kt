package com.maxrave.simpmusic.expect

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String) {
    if (!UrlDispatchPolicy.isDispatchable(url)) return
    runCatching {
        NSURL.URLWithString(url)?.let { nsUrl ->
            UIApplication.sharedApplication.openURL(nsUrl)
        }
    }
}

actual fun shareUrl(
    title: String,
    url: String,
) {
    copyToClipboard(title, url)
}
