package com.streamify.app.wear

/**
 * WearOS wire protocol models (Gap #55) — pure Kotlin, exercised on the
 * JVM shard. The GMS transport layer converts these to DataMaps/byte
 * frames; nothing here imports Android.
 */

/**
 * Compact Now Playing state pushed to the wrist. Field set mirrors what a
 * watch complication actually renders: title/artist/artwork, transport
 * flags, coarse position and the Jam context for wrist voting.
 */
data class WearNowPlayingState(
    val trackTitle: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val jamActive: Boolean = false,
    val jamQueueTop: List<WearQueueEntry> = emptyList(),
    val updatedAtMs: Long = 0L
) {
    val hasTrack: Boolean get() = trackTitle.isNotBlank()
}

/** One wrist queue row: track identity + live vote count (Jam sessions). */
data class WearQueueEntry(
    val trackId: Int,
    val title: String,
    val artist: String,
    val votes: Int = 0
)

/** Commands the watch can emit over the message channel. */
sealed class WearCommand {
    object PlayPause : WearCommand() {
        private fun readResolve(): Any = PlayPause
    }
    object SkipNext : WearCommand() {
        private fun readResolve(): Any = SkipNext
    }
    object SkipPrevious : WearCommand() {
        private fun readResolve(): Any = SkipPrevious
    }
    /** Rotary-crown / +- volume step, bounded by the wrist UI. */
    data class VolumeStep(val delta: Float) : WearCommand()
    /** One-tap Jam queue upvote (Gap #37 voting on the wrist). */
    data class JamUpvote(val trackId: Int) : WearCommand()
    /** Add the currently browsed row to the Jam queue from the watch. */
    data class QueueAdd(val videoId: String) : WearCommand()
    /** Quick-launch the user's running playlist. */
    object LaunchQuickPlaylist : WearCommand() {
        private fun readResolve(): Any = LaunchQuickPlaylist
    }
}

/**
 * Compact binary codec for the wrist command channel. One byte opcode +
 * fixed-width payloads — a DataMap-free frame that fits comfortably in a
 * MessageClient payload and round-trips identically on the JVM.
 */
object WearCommandCodec {

    private const val OP_PLAY_PAUSE: Int = 1
    private const val OP_SKIP_NEXT: Int = 2
    private const val OP_SKIP_PREVIOUS: Int = 3
    private const val OP_VOLUME_STEP: Int = 4
    private const val OP_JAM_UPVOTE: Int = 5
    private const val OP_QUEUE_ADD: Int = 6
    private const val OP_LAUNCH_QUICK_PLAYLIST: Int = 7

    /** Volume steps are clamped to ±[MAX_VOLUME_STEP] per wrist tick. */
    const val MAX_VOLUME_STEP: Float = 0.1f

    fun encode(command: WearCommand): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        fun op(v: Int) = buffer.write(v)

        when (command) {
            WearCommand.PlayPause -> op(OP_PLAY_PAUSE)
            WearCommand.SkipNext -> op(OP_SKIP_NEXT)
            WearCommand.SkipPrevious -> op(OP_SKIP_PREVIOUS)
            is WearCommand.VolumeStep -> {
                op(OP_VOLUME_STEP)
                writeFloat(buffer, command.delta)
            }
            is WearCommand.JamUpvote -> {
                op(OP_JAM_UPVOTE)
                writeInt(buffer, command.trackId)
            }
            is WearCommand.QueueAdd -> {
                op(OP_QUEUE_ADD)
                val utf = command.videoId.toByteArray(Charsets.UTF_8)
                writeInt(buffer, utf.size)
                buffer.write(utf)
            }
            WearCommand.LaunchQuickPlaylist -> op(OP_LAUNCH_QUICK_PLAYLIST)
        }
        return buffer.toByteArray()
    }

    fun decode(raw: ByteArray): WearCommand? {
        if (raw.isEmpty()) return null
        val opcode = raw[0].toInt() and 0xFF
        val body = raw.copyOfRange(1, raw.size)
        return try {
            when (opcode) {
                OP_PLAY_PAUSE -> WearCommand.PlayPause
                OP_SKIP_NEXT -> WearCommand.SkipNext
                OP_SKIP_PREVIOUS -> WearCommand.SkipPrevious
                OP_VOLUME_STEP -> {
                    if (body.size < 4) null
                    else WearCommand.VolumeStep(
                        readFloat(body, 0).coerceIn(-MAX_VOLUME_STEP, MAX_VOLUME_STEP)
                    )
                }
                OP_JAM_UPVOTE -> {
                    if (body.size < 4) null else WearCommand.JamUpvote(readInt(body, 0))
                }
                OP_QUEUE_ADD -> {
                    if (body.size < 4) null
                    else {
                        val len = readInt(body, 0)
                        if (len < 0 || len > MAX_STRING_BYTES || body.size < 4 + len) null
                        else WearCommand.QueueAdd(String(body, 4, len, Charsets.UTF_8))
                    }
                }
                OP_LAUNCH_QUICK_PLAYLIST -> WearCommand.LaunchQuickPlaylist
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_STRING_BYTES = 128

    private fun writeInt(buffer: java.io.ByteArrayOutputStream, value: Int) {
        buffer.write(value ushr 24)
        buffer.write((value ushr 16) and 0xFF)
        buffer.write((value ushr 8) and 0xFF)
        buffer.write(value and 0xFF)
    }

    private fun writeFloat(buffer: java.io.ByteArrayOutputStream, value: Float) =
        writeInt(buffer, value.toRawBits())

    private fun readInt(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xFF) shl 24) or
            ((raw[offset + 1].toInt() and 0xFF) shl 16) or
            ((raw[offset + 2].toInt() and 0xFF) shl 8) or
            (raw[offset + 3].toInt() and 0xFF)

    private fun readFloat(raw: ByteArray, offset: Int): Float =
        Float.fromBits(readInt(raw, offset))
}

/** Paths of the phone↔watch channels. */
object WearPaths {
    const val NOW_PLAYING = "/streamify/now_playing"
    const val COMMANDS = "/streamify/commands"
    const val RUN_SYNC = "/streamify/run_sync"
}
