package com.streamify.app.wear

/**
 * WearInputHandler (Gap #55) — wrist action router.
 *
 * Decodes raw command frames and dispatches them onto an injected
 * [WearActionSink]. Pure Kotlin: the JVM suite drives hostile byte frames
 * (truncated payloads, unknown opcodes, oversized strings) through it and
 * asserts nothing crashes and nothing bogus reaches the sink.
 */

/** Phone-side receivers of wrist intents. */
interface WearActionSink {
    fun playPause()
    fun skipNext()
    fun skipPrevious()

    /** @return true when the volume step was applied. */
    fun volumeStep(delta: Float): Boolean

    /** @return true when the upvote landed on a live Jam queue row. */
    fun jamUpvote(trackId: Int): Boolean

    /** @return true when the row was added to the active Jam queue. */
    fun queueAdd(videoId: String): Boolean

    /** @return true when a quick playlist actually started. */
    fun launchQuickPlaylist(): Boolean
}

/** Sink that drops everything — default until the app shell wires the real one. */
object NoopWearActionSink : WearActionSink {
    override fun playPause() {}
    override fun skipNext() {}
    override fun skipPrevious() {}
    override fun volumeStep(delta: Float): Boolean = false
    override fun jamUpvote(trackId: Int): Boolean = false
    override fun queueAdd(videoId: String): Boolean = false
    override fun launchQuickPlaylist(): Boolean = false
}

class WearInputHandler(private val sink: WearActionSink = NoopWearActionSink) {

    /**
     * Handle one raw frame from the wrist.
     * @return true when the frame decoded AND the sink accepted it.
     */
    fun handle(raw: ByteArray): Boolean {
        val command = WearCommandCodec.decode(raw) ?: return false
        return dispatch(command)
    }

    fun dispatch(command: WearCommand): Boolean = when (command) {
        WearCommand.PlayPause -> {
            sink.playPause(); true
        }
        WearCommand.SkipNext -> {
            sink.skipNext(); true
        }
        WearCommand.SkipPrevious -> {
            sink.skipPrevious(); true
        }
        is WearCommand.VolumeStep -> sink.volumeStep(command.delta)
        is WearCommand.JamUpvote -> sink.jamUpvote(command.trackId)
        is WearCommand.QueueAdd -> sink.queueAdd(command.videoId)
        WearCommand.LaunchQuickPlaylist -> sink.launchQuickPlaylist()
    }
}
