package com.streamify.app.data.network

data class ResolvedStream(
    val streamUrl: String,
    val mimeType: String = "audio/mp4",
    val bitrate: Int = 128000,
    val durationSec: Int = 0
)
