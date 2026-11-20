package com.arthenica.ffmpegkit

/**
 * ReturnCode representing execution result of FFmpeg commands.
 * Mirrors com.arthenica.ffmpegkit.ReturnCode from ffmpeg-kit-audio.
 */
class ReturnCode(val value: Int) {

    fun isValueError(): Boolean = value != 0

    companion object {
        val RETURN_CODE_SUCCESS = 0
        val RETURN_CODE_CANCEL = 255

        fun isSuccess(returnCode: ReturnCode?): Boolean =
            returnCode != null && returnCode.value == RETURN_CODE_SUCCESS

        fun isCancel(returnCode: ReturnCode?): Boolean =
            returnCode != null && returnCode.value == RETURN_CODE_CANCEL
    }
}
