package com.arthenica.ffmpegkit

/**
 * Session interface mirroring FFmpegSession / Session from ffmpeg-kit.
 */
interface Session {
    val returnCode: ReturnCode
    val state: SessionState
    val failStackTrace: String?
}

enum class SessionState {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED
}

class FFmpegSession(
    override val returnCode: ReturnCode,
    override val state: SessionState = if (ReturnCode.isSuccess(returnCode)) SessionState.COMPLETED else SessionState.FAILED,
    override val failStackTrace: String? = null
) : Session
