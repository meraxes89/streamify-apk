package com.streamify.app.connect

/**
 * Connect Models (Phase 4 — Gap #52 device picker foundation).
 *
 * Pure Kotlin data types shared by the Connect session coordinator, the
 * device registry, the Cast integration and the Wear companion. NO Android
 * imports — the whole vocabulary is exercised by the JVM unit shard.
 */

/** Physical transport that actually carries the audio for a route. */
enum class ConnectRouteKind {
    LOCAL_PHONE,
    SPEAKER,
    BLUETOOTH,
    A2DP_LE,
    CAST_RECEIVER,
    WIRED,
    CAR,
    WATCH,
    SMART_TV
}

/** Where a device row in the picker came from. */
enum class DeviceOrigin {
    THIS_PHONE,
    SYSTEM_AUDIO,
    CAST,
    LAN_DISCOVERY,
    CLOUD,
    REMEMBERED
}

/** Live connection status of a single discovered target. */
enum class DeviceLinkStatus {
    /** No session has ever been opened against this target. */
    IDLE,
    /** Handshake / route negotiation in flight. */
    CONNECTING,
    /** Actively rendering the session audio. */
    ACTIVE,
    /** Route vanished (sleep, left the house, Wi-Fi dropped). */
    LOST
}

/**
 * A Connect target as shown in the device picker bottom sheet.
 *
 * @param id stable route id — for Cast this is the Cast route id, for LAN
 *   targets the advertised target key, for this phone the literal
 *   [LOCAL_DEVICE_ID].
 * @param latencyMs last successful round-trip probe in ms (null = unknown).
 * @param supportsHandoff whether a queue/position-preserving transfer is
 *   possible (speakers & TVs yes, dumb BT sinks no — OS owns the route).
 */
data class ConnectDevice(
    val id: String,
    val name: String,
    val kind: ConnectRouteKind,
    val origin: DeviceOrigin,
    val latencyMs: Long? = null,
    val supportsHandoff: Boolean = true
) {
    val isLocal: Boolean get() = id == LOCAL_DEVICE_ID

    companion object {
        const val LOCAL_DEVICE_ID = "this-phone"

        /** The always-present first row of every picker. */
        val THIS_PHONE = ConnectDevice(
            id = LOCAL_DEVICE_ID,
            name = "This Phone",
            kind = ConnectRouteKind.LOCAL_PHONE,
            origin = DeviceOrigin.THIS_PHONE,
            latencyMs = 0L,
            supportsHandoff = true
        )
    }
}

/**
 * Route-aware volume ACL (Gap #16).
 *
 * @param remoteSliderShared the app MAY push a shared remote volume slider
 *   to the sink (Chromecast / Smart TV protocols accept stream gain).
 * @param guestLocked the app MUST NOT surface a master-gain slider for the
 *   guest — the OS / sink hardware owns absolute volume on that route
 *   (Bluetooth A2DP & LE stacks sync their own master gain).
 */
data class VolumeAcl(
    val remoteSliderShared: Boolean,
    val guestLocked: Boolean,
    val reason: String
) {
    companion object {
        fun shared(reason: String) = VolumeAcl(true, false, reason)
        fun locked(reason: String) = VolumeAcl(false, true, reason)
        fun localOnly(reason: String) = VolumeAcl(false, false, reason)
    }
}

/**
 * Single authority for the route → volume capability matrix. Both the
 * Connect sheet (slider visibility) and CastMediaManager (whether to mirror
 * CastPlayer.setVolume) consult this policy so a route can never be
 * granted two contradictory capabilities.
 */
object ConnectVolumePolicy {

    fun forRoute(kind: ConnectRouteKind): VolumeAcl = when (kind) {
        // Chromecast / Smart TV protocols carry an explicit stream-gain
        // channel: the shared slider is the intended UX.
        ConnectRouteKind.CAST_RECEIVER -> VolumeAcl.shared("Receiver exposes a shared stream-gain channel")
        ConnectRouteKind.SMART_TV -> VolumeAcl.shared("TV protocols accept remote stream gain")
        // Bluetooth stacks own absolute master gain at the OS layer; pushing
        // a second slider desyncs from hardware volume and double-attenuates.
        ConnectRouteKind.BLUETOOTH -> VolumeAcl.locked("Bluetooth OS stack owns master gain")
        ConnectRouteKind.A2DP_LE -> VolumeAcl.locked("A2DP LE hardware owns master gain")
        // Wired / built-in speaker gain belongs to the phone itself — a
        // local-only slider, no remote sharing.
        ConnectRouteKind.WIRED -> VolumeAcl.localOnly("Wired output uses the phone's local gain")
        ConnectRouteKind.SPEAKER -> VolumeAcl.localOnly("Built-in speaker uses the phone's local gain")
        ConnectRouteKind.CAR -> VolumeAcl.locked("Car head-unit hardware owns master gain")
        ConnectRouteKind.WATCH -> VolumeAcl.localOnly("Watch speakers use the watch's local gain")
        ConnectRouteKind.LOCAL_PHONE -> VolumeAcl.localOnly("Local playback uses the phone's local gain")
    }
}

/**
 * Position + queue fingerprint captured at the moment of a transfer so the
 * receiving route resumes EXACTLY where the donor left off (no restart, no
 * queue reorder). Mirrors the Jam lockstep ApplyTrack semantics.
 */
data class PlaybackSnapshot(
    val queueTitles: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val volume: Float = 1f
) {
    val queueSize: Int get() = queueTitles.size
}

/** Commands the silent remote-controller forwards to the active sink. */
sealed class ConnectCommand {
    data class PlayPause(val play: Boolean) : ConnectCommand()
    data class Seek(val positionMs: Long) : ConnectCommand()
    /** Continuous scrub drag (coalesced by the caller before forwarding). */
    data class Scrub(val positionMs: Long) : ConnectCommand()
    data class SetVolume(val volume: Float) : ConnectCommand()
    data class QueueReorder(val fromIndex: Int, val toIndex: Int) : ConnectCommand()
    object SkipNext : ConnectCommand() {
        private fun readResolve(): Any = SkipNext
    }
    object SkipPrevious : ConnectCommand() {
        private fun readResolve(): Any = SkipPrevious
    }
}

/** Events flowing back from a connected sink into the coordinator. */
sealed class ConnectGatewayEvent {
    /** Sink-reported playback position (drift telemetry + flip-back seed). */
    data class RemotePosition(val positionMs: Long, val isPlaying: Boolean) : ConnectGatewayEvent()
    /** Sink-side volume changed by another controller on the same route. */
    data class RemoteVolume(val volume: Float) : ConnectGatewayEvent()
    /** Route dropped — coordinator must fall back to local playback. */
    object Disconnected : ConnectGatewayEvent() {
        private fun readResolve(): Any = Disconnected
    }
}

/** Phase of the single Connect session owned by the coordinator. */
enum class ConnectSessionPhase {
    /** Audio renders on this phone; no remote sink bound. */
    LOCAL_ONLY,
    /** Handshake with the selected target in flight. */
    CONNECTING,
    /** A remote sink renders; the local player is a silent controller. */
    REMOTE_ACTIVE,
    /** Remote died mid-session; local playback is being restored. */
    FALLING_BACK
}

/**
 * Immutable session state published to every UI surface (picker sheet,
 * Full Player "Listening on …" pill, MiniPlayer pill, Wear controller).
 */
data class ConnectSessionState(
    val phase: ConnectSessionPhase = ConnectSessionPhase.LOCAL_ONLY,
    val activeDevice: ConnectDevice? = null,
    val snapshotAtTransfer: PlaybackSnapshot? = null,
    val lastTransferAtMs: Long = 0L,
    val lastRemotePositionMs: Long = 0L,
    val remoteIsPlaying: Boolean = false,
    /** Human-facing transfer outcome for snackbar surfaces. */
    val lastError: String? = null
) {
    val isRemoteActive: Boolean get() = phase == ConnectSessionPhase.REMOTE_ACTIVE
    val activeDeviceName: String? get() = activeDevice?.takeIf { !it.isLocal }?.name
    val renderMode: LocalRenderMode
        get() = if (isRemoteActive) LocalRenderMode.SILENT_CONTROLLER else LocalRenderMode.FULL_RENDER
}

/**
 * How the local ExoPlayer engine behaves while a remote sink renders.
 * SILENT_CONTROLLER keeps the queue + clock alive locally (volume muted or
 * paused) so a flip back to this phone is a seek — not a reload — exactly
 * like JamEngine's ApplyRenderSuppression party-mode discipline.
 */
enum class LocalRenderMode { FULL_RENDER, SILENT_CONTROLLER }
