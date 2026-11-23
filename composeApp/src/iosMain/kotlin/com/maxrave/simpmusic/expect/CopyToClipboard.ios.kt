package com.maxrave.simpmusic.expect

import platform.UIKit.UIPasteboard

actual fun copyToClipboard(
    label: String,
    text: String,
) {
    runCatching {
        UIPasteboard.generalPasteboard.string = text
    }
}
