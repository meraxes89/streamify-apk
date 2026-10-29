package com.streamify.app.data

import com.streamify.app.data.models.OrchestratorStatusNative
import com.streamify.app.data.models.RecommendationNative
import com.streamify.app.data.models.TrackNative
import com.streamify.app.util.SLog
import com.streamify.app.data.repository.TrackRepository

object NativeBridge {
    init {
        try {
            System.loadLibrary("streamify_core_rs")
        } catch (e: UnsatisfiedLinkError) {
            SLog.st("NativeBridge", "loadLibrary(streamify_core_rs) failed", e)
        }
        try {
            System.loadLibrary("streamify_native_core")
        } catch (e: UnsatisfiedLinkError) {
            SLog.st("NativeBridge", "loadLibrary(streamify_native_core) failed", e)
        }
        try {
            System.loadLibrary("streamify_core")
        } catch (e: UnsatisfiedLinkError) {
            SLog.st("NativeBridge", "loadLibrary(streamify_core) failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 3: C++20 DSP & 128-D VECTOR STORE INTERFACES
    // ═══════════════════════════════════════════════════════════════
    fun pinToLittleCores(): Boolean = try {
        nativePinToLittleCores()
    } catch (e: Throwable) {
        false
    }

    fun processLivePcmTap(directBuffer: java.nio.ByteBuffer, sampleCount: Int): Float {
        if (!directBuffer.isDirect) throw IllegalArgumentException("Buffer must be allocated direct")
        return try {
            nativeProcessPcmTap(directBuffer, sampleCount)
        } catch (e: Throwable) {
            1.0f
        }
    }

    fun insertVector(trackId: Long, embedding: FloatArray) {
        require(embedding.size == 128) { "Embedding must be exactly 128 dimensions" }
        try {
            nativeInsertVector(trackId, embedding)
        } catch (e: Throwable) {
            SLog.st("NativeBridge", "NativeBridge.insertVector failed", e)
        }
    }

    fun queryTopK(targetEmbedding: FloatArray, k: Int): LongArray {
        require(targetEmbedding.size == 128) { "Embedding must be exactly 128 dimensions" }
        return try {
            nativeQueryTopK(targetEmbedding, k) ?: LongArray(0)
        } catch (e: Throwable) {
            LongArray(0)
        }
    }

    private external fun nativePinToLittleCores(): Boolean
    private external fun nativeProcessPcmTap(directBuffer: java.nio.ByteBuffer, sampleCount: Int): Float
    private external fun nativeInsertVector(trackId: Long, embedding: FloatArray)
    private external fun nativeQueryTopK(targetEmbedding: FloatArray, k: Int): LongArray?

    // ═══════════════════════════════════════════════════════════════
    // PHASE 4: SLYR PARSER & WIENER-KHINCHIN LYRIC ALIGNMENT
    // ═══════════════════════════════════════════════════════════════
    fun findActiveSlyrLine(slyrBytes: ByteArray, slyrLen: Int, playheadMs: Int): Int {
        return try {
            nativeFindActiveSlyrLine(slyrBytes, slyrLen, playheadMs)
        } catch (e: Throwable) {
            -1
        }
    }

    fun calculateDriftOffset(
        vocalEnergy: FloatArray, vocalLen: Int,
        lyricOnsets: FloatArray, lyricLen: Int
    ): Int {
        return try {
            nativeCalculateDriftOffset(vocalEnergy, vocalLen, lyricOnsets, lyricLen)
        } catch (e: Throwable) {
            0
        }
    }

    private external fun nativeFindActiveSlyrLine(
        slyrBuffer: ByteArray, slyrLen: Int, playheadMs: Int
    ): Int

    private external fun nativeCalculateDriftOffset(
        vocalEnergy: FloatArray, vocalLen: Int,
        lyricOnsets: FloatArray, lyricLen: Int
    ): Int

    private const val BUFFER_SIZE = 512

    // ═══════════════════════════════════════════════════════════════
    // PHASE 2: JIT STREAM RESOLVER & CDN EXTRACTION
    // ═══════════════════════════════════════════════════════════════
    private external fun nativeResolveStreamFormats(videoId: String, dbPath: String): String?

    fun resolveStreamFormats(videoId: String, dbPath: String = ""): List<com.streamify.app.data.network.ResolvedStream>? {
        val json = try {
            nativeResolveStreamFormats(videoId, dbPath)
        } catch (e: Throwable) {
            SLog.e("NativeBridge", "nativeResolveStreamFormats failed for $videoId", e)
            null
        } ?: return null

        if (json.isBlank()) return null
        return try {
            val array = org.json.JSONArray(json)
            val list = mutableListOf<com.streamify.app.data.network.ResolvedStream>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url")
                val mimeType = obj.optString("mime_type", "audio/mp4")
                val bitrate = obj.optInt("bitrate", 128000)
                val durationSec = obj.optInt("duration_sec", 0)
                if (url.isNotBlank()) {
                    list.add(
                        com.streamify.app.data.network.ResolvedStream(
                            streamUrl = url,
                            mimeType = mimeType,
                            bitrate = bitrate,
                            durationSec = durationSec
                        )
                    )
                }
            }
            list.ifEmpty { null }
        } catch (e: Throwable) {
            SLog.e("NativeBridge", "Failed to parse stream formats JSON: $json", e)
            null
        }
    }

    private const val VIDEO_ID_BUFFER_SIZE = 16
    private val videoIdBuffer = ByteArray(VIDEO_ID_BUFFER_SIZE)

    private external fun nativeResolveTrack(
        dbPath: String,
        cadId: String,
        isrc: String?,
        title: String,
        artist: String,
        authHeader: String,
        cookies: ByteArray,
        cookiesLen: Int,
        outBuffer: ByteArray
    ): Int

    suspend fun resolveTrack(
        dbPath: String,
        track: com.streamify.app.ui.models.VirtualShelfTrack,
        authHeader: String = "",
        cookies: String = ""
    ): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cookiesBytes = cookies.toByteArray(Charsets.UTF_8)
        val cookiesLen = cookiesBytes.size
        synchronized(videoIdBuffer) {
            videoIdBuffer.fill(0)
            val result = try {
                nativeResolveTrack(
                    dbPath,
                    track.cadId,
                    track.isrc,
                    track.title,
                    track.artist,
                    authHeader,
                    cookiesBytes, cookiesLen,
                    videoIdBuffer
                )
            } catch (e: Throwable) {
                -3
            }

            if (result > 0) {
                String(videoIdBuffer, 0, result, Charsets.UTF_8)
            } else {
                null
            }
        }
    }

    suspend fun resolveTrack(
        dbPath: String,
        cadId: String,
        isrc: String?,
        title: String,
        artist: String,
        authHeader: String = "",
        cookies: String = ""
    ): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cookiesBytes = cookies.toByteArray(Charsets.UTF_8)
        val cookiesLen = cookiesBytes.size
        synchronized(videoIdBuffer) {
            videoIdBuffer.fill(0)
            val result = try {
                nativeResolveTrack(
                    dbPath,
                    cadId,
                    isrc,
                    title,
                    artist,
                    authHeader,
                    cookiesBytes, cookiesLen,
                    videoIdBuffer
                )
            } catch (e: Throwable) {
                -3
            }

            if (result > 0) {
                String(videoIdBuffer, 0, result, Charsets.UTF_8)
            } else {
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 1: SAPISID AUTH & CANONICAL AUDIO DESCRIPTOR (CAD)
    // ═══════════════════════════════════════════════════════════════
    private const val HASH_BUFFER_SIZE = 128
    private val hashBuffer = ByteArray(HASH_BUFFER_SIZE)

    private external fun nativeGenerateSapisidHash(
        sapisid: String,
        origin: String,
        outBuffer: ByteArray
    ): Int

    fun generateSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String? {
        if (sapisid.isBlank()) return null
        synchronized(hashBuffer) {
            val result = try {
                nativeGenerateSapisidHash(
                    sapisid.trim(),
                    origin.trim(),
                    hashBuffer
                )
            } catch (e: Throwable) {
                -3
            }

            if (result <= 0) return null
            return String(hashBuffer, 0, result, Charsets.UTF_8)
        }
    }

    fun getSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String? {
        val rawHash = generateSapisidHash(sapisid, origin) ?: return null
        return if (rawHash.startsWith("SAPISIDHASH ")) rawHash else "SAPISIDHASH $rawHash"
    }

    fun generateCadId(title: String, artist: String, durationSec: Int): String {
        return try {
            nativeGenerateCadId(title, artist, durationSec)
        } catch (e: Throwable) {
            fallbackGenerateCadId(title, artist, durationSec)
        }
    }

    /**
     * Pure-Kotlin mirror of the canonical CAD-ID scheme, used only when the native
     * library fails to load. MUST stay byte-identical to:
     *  - C++: nativeGenerateCadId (native/jni/jni_bridge.cc)
     *  - Rust: TrackRepository::generate_cad_id (rust/src/repository.rs)
     * Algorithm: FNV-1a 64-bit over ASCII-byte-filtered lowercase title ('(' kept),
     * ASCII-filtered artist, then the little-endian u32 duration bucket (/3).
     */
    private fun fallbackGenerateCadId(title: String, artist: String, durationSec: Int): String {
        val offset = 14695981039346656037UL
        val prime = 1099511628211UL

        fun normalize(source: String, keepParen: Boolean): List<Int> {
            val bytes = mutableListOf<Int>()
            for (raw in source) {
                if (raw.code in 0..127) {
                    val c = raw.lowercaseChar()
                    val isAlnum = c in 'a'..'z' || c in '0'..'9'
                    if (isAlnum || (keepParen && c == '(')) {
                        bytes.add(c.code)
                    }
                }
            }
            return bytes
        }

        var hash = offset
        for (b in normalize(title, keepParen = true)) {
            hash = (hash xor b.toULong()) * prime
        }
        for (b in normalize(artist, keepParen = false)) {
            hash = (hash xor b.toULong()) * prime
        }
        val bucket = if (durationSec > 0) durationSec / 3 else 0
        for (i in 0 until 4) {
            hash = (hash xor ((bucket shr (i * 8)) and 0xFF).toULong()) * prime
        }
        return hash.toString(16).padStart(16, '0')
    }

    external fun nativeGenerateCadId(title: String, artist: String, durationSec: Int): String

    // ═══════════════════════════════════════════════════════════════
    // PHASE 3: SPOTIFY PKCE AUTH & NATIVE LIBRARY INGESTION
    // ═══════════════════════════════════════════════════════════════
    private val accessTokenBuffer = ByteArray(512)
    private val refreshTokenBuffer = ByteArray(512)

    private external fun spotifyExchangePkce(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
        outAccess: ByteArray,
        outRefresh: ByteArray
    ): Int

    private external fun spotifyIngestLibrary(
        dbPath: String,
        accessToken: String
    ): Int

    fun exchangeSpotifyPkce(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String
    ): Pair<String, String>? {
        synchronized(accessTokenBuffer) {
            accessTokenBuffer.fill(0)
            refreshTokenBuffer.fill(0)
            val result = try {
                spotifyExchangePkce(
                    code.trim(),
                    verifier.trim(),
                    redirectUri.trim(),
                    clientId.trim(),
                    accessTokenBuffer,
                    refreshTokenBuffer
                )
            } catch (e: Throwable) {
                -3
            }

            if (result == 0) {
                val access = String(accessTokenBuffer, Charsets.UTF_8).trimEnd('\u0000').trim()
                val refresh = String(refreshTokenBuffer, Charsets.UTF_8).trimEnd('\u0000').trim()
                if (access.isNotBlank()) {
                    return Pair(access, refresh)
                }
            }
            return null
        }
    }

    suspend fun ingestSpotifyLibraryNative(
        dbPath: String,
        accessToken: String
    ): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            spotifyIngestLibrary(dbPath.trim(), accessToken.trim())
        } catch (e: Throwable) {
            -3
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 4: 32-BIT FLOAT NATIVE DSP AUDIO ENGINE
    // ═══════════════════════════════════════════════════════════════
    external fun nativeInitDsp(): Long
    external fun nativeFreeDsp(statePtr: Long)
    external fun nativeProcessDsp(
        statePtr: Long,
        input: java.nio.ByteBuffer,
        output: java.nio.ByteBuffer,
        numFrames: Int
    ): Int

    /**
     * Fused single-crossing render pass: i16 PCM in -> DSP/EQ -> RMS
     * normalization -> f32 out. One JNI transition per audio buffer instead
     * of two, zero intermediate copies on the real-time thread.
     */
    external fun nativeProcessFusedAudio(
        statePtr: Long,
        normalizerPtr: Long,
        input: java.nio.ByteBuffer,
        output: java.nio.ByteBuffer,
        numFrames: Int
    ): Int

    // ═══════════════════════════════════════════════════════════════
    // PHASE 5: INFINITE DYNAMIC QUEUE & BACKGROUND DELTA SYNC
    // ═══════════════════════════════════════════════════════════════
    private val nextCadBuffer = ByteArray(32)
    private val nextVidBuffer = ByteArray(16)

    private external fun nativeGetNextTrack(
        dbPath: String,
        currentCadId: String,
        isShuffle: Boolean,
        outCad: ByteArray,
        outVideo: ByteArray
    ): Int

    private external fun spotifyDeltaSync(
        dbPath: String,
        accessToken: String,
        lastSyncTimestamp: Long
    ): Int

    private external fun nativeShutdown(dbPath: String)

    fun getNextTrack(
        dbPath: String,
        currentCadId: String,
        isShuffle: Boolean
    ): Pair<String, String>? {
        if (dbPath.isBlank()) return null
        synchronized(nextCadBuffer) {
            nextCadBuffer.fill(0)
            nextVidBuffer.fill(0)
            val result = try {
                nativeGetNextTrack(
                    dbPath.trim(),
                    currentCadId.trim(),
                    isShuffle,
                    nextCadBuffer,
                    nextVidBuffer
                )
            } catch (e: Throwable) {
                -3
            }

            if (result > 0) {
                val cadId = String(nextCadBuffer, 0, result, Charsets.UTF_8).trim()
                val vidLen = nextVidBuffer.indexOf(0).takeIf { it >= 0 } ?: nextVidBuffer.size
                val videoId = String(nextVidBuffer, 0, vidLen, Charsets.UTF_8).trim()
                if (cadId.isNotBlank()) {
                    return Pair(cadId, videoId)
                }
            }
            return null
        }
    }

    suspend fun performSpotifyDeltaSync(
        dbPath: String,
        accessToken: String,
        lastSyncTimestamp: Long
    ): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            spotifyDeltaSync(dbPath.trim(), accessToken.trim(), lastSyncTimestamp)
        } catch (e: Throwable) {
            -1
        }
    }

    fun shutdown(dbPath: String = "") {
        try {
            nativeShutdown(dbPath)
        } catch (e: Throwable) {
            // Ignore
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 6: ZERO-LATENCY ENCRYPTED CACHE & 120 FPS LYRICS ENGINE
    // ═══════════════════════════════════════════════════════════════
    external fun nativeParseLrc(lrcText: String): Long
    external fun nativeGetLyricIndex(mapPtr: Long, currentTimeMs: Long): Int
    external fun nativeFreeLyricMap(mapPtr: Long)
    external fun nativeCryptCacheChunk(
        inputBuf: ByteArray,
        outputBuf: ByteArray,
        len: Int,
        keyBuf: ByteArray,
        offset: Long
    ): Int

    // ═══════════════════════════════════════════════════════════════
    // PHASE 7: ADAPTIVE THERMAL GOVERNOR & OS LIFECYCLE RESILIENCY
    // ═══════════════════════════════════════════════════════════════
    private external fun nativeUpdateThermalStatus(status: Int): Int
    private external fun nativeGetThermalStatus(): Int
    private external fun nativeFlushDatabaseWal(dbPath: String): Int

    // ═══════════════════════════════════════════════════════════════
    // JAM PHASE-1 SYNC CORE (jam_clock / tick_matrix / kalman_pll)
    // ═══════════════════════════════════════════════════════════════

    private external fun nativeJamClockReset()
    private external fun nativeJamClockApplySample(
        t0: Long, t1: Long, t2: Long, t3: Long, outResults: LongArray
    )
    private external fun nativeGetSyncedJamMonotonicMs(): Long
    private external fun nativeGetLocalMonotonicMs(): Long
    private external fun nativeJamClockRttMs(): Long
    private external fun nativeJamTickIngest(
        seq: Int,
        posMs: Long,
        hostMonoMs: Long,
        state: Int,
        policy: Int,
        outPacked: LongArray
    ): Int
    private external fun nativeKalmanPllReset()
    private external fun nativeKalmanPllDecide(
        zPosMs: Long,
        nowSyncedMs: Long,
        tickHostMonoMs: Long,
        playing: Boolean,
        outResults: LongArray
    ): Int
    private external fun nativeTickMatrixDiagnostics(outResults: LongArray)
    private external fun nativeJamTickMatrixReset()

    /** Wipe all Jam clock state (call on session end / role change). */
    fun jamClockReset() {
        try { nativeJamClockReset() } catch (_: Throwable) {}
    }

    /**
     * Cristian handshake fusion. Returns [thetaMs, deltaMs] as accepted by the
     * native best-sample/EMA filter (may differ from raw math).
     */
    fun jamClockApplySample(t0: Long, t1: Long, t2: Long, t3: Long): LongArray {
        return try {
            val out = LongArray(2)
            nativeJamClockApplySample(t0, t1, t2, t3, out)
            out
        } catch (_: Throwable) {
            longArrayOf(0L, -1L)
        }
    }

    /**
     * THE Jam time source: local monotonic mapped into the host's monotonic
     * timeline. Skew-free — never use System.currentTimeMillis() for sync math.
     */
    fun getSyncedJamMonotonicMs(): Long {
        return try { nativeGetSyncedJamMonotonicMs() } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }

    /**
     * Raw device-local monotonic (offset-free). Handshake endpoints t0/t3 MUST
     * use this; synced values would feed theta back into itself.
     */
    fun getLocalMonotonicMs(): Long {
        return try { nativeGetLocalMonotonicMs() } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }

    /** Last handshake round-trip in ms (-1 = no sample yet). */
    fun getJamClockRttMs(): Long {
        return try { nativeJamClockRttMs() } catch (_: Throwable) { -1L }
    }

    /**
     * Feed a received host tick into the lossless sequence matrix.
     * Returns synthesized+real packed entries [seq<<32 | posMs] in order;
     * gap-fills precede the real tick. Empty array when dropped/duplicate.
     */
    fun jamTickIngest(seq: Int, posMs: Long, hostMonoMs: Long, state: Int, policy: Int): LongArray {
        return try {
            val out = LongArray(64)
            val n = nativeJamTickIngest(seq, posMs, hostMonoMs, state, policy, out)
            if (n > 0) out.copyOf(n) else LongArray(0)
        } catch (_: Throwable) {
            longArrayOf(((seq.toLong()) shl 32) or (posMs and 0x7FFFFFFFL))
        }
    }

    /** Reset the Kalman PLL (regime changes, track transitions, role flips). */
    fun kalmanPllReset() {
        try { nativeKalmanPllReset() } catch (_: Throwable) {}
    }

    /**
     * Kalman decision point. Returns [decision, speedMilli, seekTargetMs]:
     *   decision 0=HOLD 1=SPEED 2=SEEK
     *   speedMilli: target ×1000 (e.g. 1002 → 1.002x), valid on SPEED
     *   seekTargetMs: hard-seek target, valid on SEEK
     */
    fun kalmanPllDecide(
        zPosMs: Long,
        nowSyncedMs: Long,
        tickHostMonoMs: Long,
        playing: Boolean
    ): LongArray {
        return try {
            val out = LongArray(3)
            nativeKalmanPllDecide(zPosMs, nowSyncedMs, tickHostMonoMs, playing, out)
            out
        } catch (_: Throwable) {
            longArrayOf(0L, 1000L, -1L)
        }
    }

    /** Tick matrix diagnostics: [synthesized_total, max_gap_seen, ema_interval_ms]. */
    fun getTickMatrixDiagnostics(): LongArray {
        return try {
            val out = LongArray(3)
            nativeTickMatrixDiagnostics(out)
            out
        } catch (_: Throwable) {
            longArrayOf(-1L, -1L, -1L)
        }
    }

    /** PHASE 4: wipe tick sequence state on HOST_TAKEOVER (both sides). */
    fun jamTickMatrixReset() {
        try { nativeJamTickMatrixReset() } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // JAM PHASE-2 STATE CORE (jam_crdt / jam_outbox)
    // ═══════════════════════════════════════════════════════════════

    private external fun nativeJamCrdtReset()
    private external fun nativeJamCrdtApplyOp(
        opId: Long,
        senderPacked: Long,
        opType: Int,
        policyFlags: Int,
        cadId: Long,
        fracBits: Long,
        targetAddOpId: Long,
        checksum: Long // -1 => seal locally
    ): Int
    private external fun nativeJamCrdtFold(outQueue: LongArray, outTomb: LongArray): Int
    private external fun nativeJamGenerateOpId(): Long
    private external fun nativeJamCanonicalCadId(title: String, artist: String, durationSec: Int): Long
    private external fun nativeJamOutboxOpen(dbPath: String): Int
    private external fun nativeJamOutboxEnqueue(
        opId: Long,
        senderPacked: Long,
        opType: Int,
        policyFlags: Int,
        cadId: Long,
        fracBits: Long,
        targetAddOpId: Long,
        sessionCode: String
    ): Boolean
    private external fun nativeJamOutboxPoll(sessionCode: String, limit: Int, outOps: LongArray): Int
    private external fun nativeJamOutboxAck(opIds: LongArray): Int
    private external fun nativeJamOutboxReplay(staleThresholdMs: Long): Int
    private external fun nativeJamOutboxPendingCount(sessionCode: String): Int
    private external fun nativeJamOutboxGc(keepMs: Long): Int

    /** Wipe the CRDT queue/tombstones (session end / role change). */
    fun jamCrdtReset() {
        try { nativeJamCrdtReset() } catch (_: Throwable) {}
    }

    /**
     * Applies a LOCAL UI-generated op: checksum sealed natively. Returns true
     * when the state machine accepted it.
     */
    fun jamCrdtApplyLocalOp(
        opId: Long,
        senderPacked: Long,
        opType: Int,
        policyFlags: Int,
        cadId: Long,
        fracIndex: Double,
        targetAddOpId: Long
    ): Boolean = try {
        nativeJamCrdtApplyOp(
            opId, senderPacked, opType, policyFlags, cadId,
            fracIndex.toRawBits(), targetAddOpId, -1L
        ) == 1
    } catch (_: Throwable) {
        false
    }

    /**
     * Applies a WIRE-received op: the transmitted checksum is honored and
     * validated before merge.
     */
    fun jamCrdtApplyWireOp(
        opId: Long,
        senderPacked: Long,
        opType: Int,
        policyFlags: Int,
        cadId: Long,
        fracIndex: Double,
        targetAddOpId: Long,
        checksum: Long
    ): Boolean = try {
        nativeJamCrdtApplyOp(
            opId, senderPacked, opType, policyFlags, cadId,
            fracIndex.toRawBits(), targetAddOpId, checksum
        ) == 1
    } catch (_: Throwable) {
        false
    }

    /**
     * Folds the live CRDT into [frac, addOpId, cadId] triplets + tombstones.
     * Returns null when nothing is initialized.
     */
    fun jamCrdtFold(): Pair<LongArray, LongArray>? {
        return try {
            val q = LongArray(2048 * 3)
            val t = LongArray(2048)
            val n = nativeJamCrdtFold(q, t)
            if (n <= 0) null else q.copyOf(n * 3) to t
        } catch (_: Throwable) {
            null
        }
    }

    /** Strictly monotonic mutation id (clock-step-back immune). */
    fun jamGenerateOpId(): Long {
        return try { nativeJamGenerateOpId() } catch (_: Throwable) {
            System.nanoTime()
        }
    }

    /** Canonical CAD-ID u64 — identical value across every subsystem. */
    fun jamCanonicalCadId(title: String, artist: String, durationSec: Int): Long {
        jamCadIdOverride?.let { return it(title, artist, durationSec) }
        return try { nativeJamCanonicalCadId(title, artist, durationSec) } catch (_: Throwable) {
            0L
        }
    }

    /** Opens the WAL-backed outbox journal. Call once per process on join. */
    fun jamOutboxOpen(dbPath: String): Boolean {
        return try { nativeJamOutboxOpen(dbPath) == 1 } catch (_: Throwable) { false }
    }

    /** Local-first mutation persist. Returns true when newly written. */
    fun jamOutboxEnqueue(
        opId: Long,
        senderPacked: Long,
        opType: Int,
        policyFlags: Int,
        cadId: Long,
        fracIndex: Double,
        targetAddOpId: Long,
        sessionCode: String
    ): Boolean = try {
        nativeJamOutboxEnqueue(
            opId, senderPacked, opType, policyFlags, cadId,
            fracIndex.toRawBits(), targetAddOpId, sessionCode
        )
    } catch (_: Throwable) {
        false
    }

    /**
     * Drains up to [limit] pending ops for a session. Each op packs 7 longs:
     * [opId, type|policy<<8|nonce, cad, fracBits, target, checksum, 0].
     */
    fun jamOutboxPoll(sessionCode: String, limit: Int): LongArray {
        return try {
            val out = LongArray(limit.coerceIn(1, 256) * 7)
            val n = nativeJamOutboxPoll(sessionCode, limit, out)
            if (n > 0) out.copyOf(n * 7) else LongArray(0)
        } catch (_: Throwable) {
            LongArray(0)
        }
    }

    /** Host ratified these op ids — purge from the journal. */
    fun jamOutboxAck(opIds: LongArray): Int {
        return try { nativeJamOutboxAck(opIds) } catch (_: Throwable) { 0 }
    }

    /** Partition heal / periodic stale sweep. Returns reverted count. */
    fun jamOutboxReplay(staleThresholdMs: Long = 30_000L): Int {
        return try { nativeJamOutboxReplay(staleThresholdMs) } catch (_: Throwable) { 0 }
    }

    /** Backlog depth for "syncing N edits" UI badges. */
    fun jamOutboxPendingCount(sessionCode: String): Int {
        return try { nativeJamOutboxPendingCount(sessionCode) } catch (_: Throwable) { 0 }
    }

    fun jamOutboxGc(keepMs: Long = 24 * 60 * 60 * 1000L): Int {
        return try { nativeJamOutboxGc(keepMs) } catch (_: Throwable) { 0 }
    }

    // ── PHASE 3: governor (election + death pivot) ──────────────────────

    private external fun nativeJamElectSuccessor(
        participantsJoined: String, hostId: String, leaseExpired: Boolean
    ): String?
    private external fun nativeJamExtrapolatePivot(
        lastPosMs: Long,
        lastTickMonoMs: Long,
        currentSyncedMonoMs: Long,
        durationMs: Long,
        trackMatches: Boolean,
        outResults: LongArray
    )
    private external fun nativeJamIsAdvisorySuccessor(
        participantsJoined: String, hostId: String, selfId: String, recentlySeen: Boolean
    ): Boolean

    /** Deterministic successor (lowercase-hex rule); null when room should end. */
    fun jamElectSuccessor(participantIds: List<String>, hostId: String, leaseExpired: Boolean): String? {
        return try {
            val joined = participantIds.joinToString(",")
            nativeJamElectSuccessor(joined, hostId, leaseExpired)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Death-pivot decision. Returns [code, posMs]:
     *   code 0 = OK (pivot to posMs), 1 = BEYOND_END, 2 = TRACK_MISMATCH
     */
    fun jamExtrapolatePivot(
        lastPosMs: Long,
        lastTickMonoMs: Long,
        currentSyncedMonoMs: Long,
        durationMs: Long,
        trackMatches: Boolean
    ): LongArray {
        return try {
            val out = LongArray(2)
            nativeJamExtrapolatePivot(lastPosMs, lastTickMonoMs, currentSyncedMonoMs, durationMs, trackMatches, out)
            out
        } catch (_: Throwable) {
            longArrayOf(1L, -1L) // fail safe: normal advance
        }
    }

    /** Client-side advisory only — the server RPC remains the sole grantor. */
    fun jamIsAdvisorySuccessor(
        participantIds: List<String>,
        hostId: String,
        selfId: String,
        recentlySeen: Boolean
    ): Boolean {
        return try {
            nativeJamIsAdvisorySuccessor(participantIds.joinToString(","), hostId, selfId, recentlySeen)
        } catch (_: Throwable) {
            false
        }
    }



    fun updateThermalStatus(status: Int): Int {
        return try {
            nativeUpdateThermalStatus(status)
        } catch (e: Throwable) {
            -1
        }
    }

    fun getThermalStatus(): Int {
        return try {
            nativeGetThermalStatus()
        } catch (e: Throwable) {
            0
        }
    }

    fun flushDatabaseWal(dbPath: String): Int {
        if (dbPath.isBlank()) return -1
        return try {
            nativeFlushDatabaseWal(dbPath.trim())
        } catch (e: Throwable) {
            -1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 8: REAL-TIME RMS VOLUME NORMALIZER
    // ═══════════════════════════════════════════════════════════════
    external fun nativeInitNormalizer(targetRms: Float): Long
    external fun nativeFreeNormalizer(statePtr: Long)
    external fun nativeApplyNormalization(
        statePtr: Long,
        pcmBuffer: java.nio.ByteBuffer,
        numFrames: Int
    ): Int

    // ═══════════════════════════════════════════════════════════════
    // PHASE 9: LOCK-FREE ATOMIC SEEK DEBOUNCER
    // ═══════════════════════════════════════════════════════════════
    private external fun nativeSubmitSeekRequest(positionMs: Long): Int
    private external fun nativeConsumePendingSeek(debounceMs: Long): Long
    private external fun nativeResetSeekGuard()

    fun submitSeekRequest(positionMs: Long): Int {
        return try {
            nativeSubmitSeekRequest(positionMs)
        } catch (e: Throwable) {
            -1
        }
    }

    fun consumePendingSeek(debounceMs: Long = 150L): Long {
        return try {
            nativeConsumePendingSeek(debounceMs)
        } catch (e: Throwable) {
            -1L
        }
    }

    fun resetSeekGuard() {
        try {
            nativeResetSeekGuard()
        } catch (e: Throwable) {
            // Ignore
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE 10: CONTEXTUAL KINETIC TRAJECTORY ENGINE
    // ═══════════════════════════════════════════════════════════════
    external fun nativeInitContinuumState(): Long
    external fun nativeFreeContinuumState(statePtr: Long)
    external fun nativeEvaluateContinuum(
        statePtr: Long,
        candidates: FloatArray,
        outScores: FloatArray
    ): Int
    external fun nativeCommitTrackToContinuum(
        statePtr: Long,
        trackVector: FloatArray,
        artistHash: Long,
        trackHash: Long,
        dwellPercentage: Float
    ): Int

    // 1MB pre-allocated DirectByteBuffer for virtual shelf binary streaming (~500 tracks)
    private val shelfBuffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(1024 * 1024)

    private external fun nativeIngestSpotifyTracks(dbPath: String, jsonPayload: String): Int
    private external fun nativeFetchVirtualShelf(dbPath: String, outBuffer: java.nio.ByteBuffer): Int

    fun ingestSpotifyTracks(dbPath: String, jsonPayload: String): Boolean {
        return try {
            nativeIngestSpotifyTracks(dbPath, jsonPayload) >= 0
        } catch (e: Throwable) {
            false
        }
    }

    fun fetchVirtualShelf(dbPath: String): List<com.streamify.app.ui.models.VirtualShelfTrack> {
        val result = synchronized(shelfBuffer) {
            try {
                shelfBuffer.clear()
                val written = nativeFetchVirtualShelf(dbPath, shelfBuffer)
                if (written < 0) return emptyList()

                shelfBuffer.position(0)
                shelfBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val trackCount = shelfBuffer.int
                val tracks = ArrayList<com.streamify.app.ui.models.VirtualShelfTrack>(trackCount.coerceAtLeast(0))

                for (i in 0 until trackCount) {
                    val cadId = readBinaryString(shelfBuffer)
                    val title = readBinaryString(shelfBuffer)
                    val artist = readBinaryString(shelfBuffer)
                    val artworkUrl = readBinaryString(shelfBuffer)

                    tracks.add(
                        com.streamify.app.ui.models.VirtualShelfTrack(
                            cadId = cadId,
                            title = title,
                            artist = artist,
                            artworkUrl = artworkUrl,
                            durationSec = 0,
                            isrc = null,
                            ytmVideoId = null,
                            isLiked = false,
                            platformOrigin = "SPOTIFY"
                        )
                    )
                }
                tracks
            } catch (e: Throwable) {
                emptyList()
            }
        }
        return result
    }

    private fun readBinaryString(buf: java.nio.ByteBuffer): String {
        if (buf.remaining() < 4) return ""
        val len = buf.int
        if (len <= 0 || buf.remaining() < len) return ""
        val bytes = ByteArray(len)
        buf.get(bytes, 0, len)
        return String(bytes, Charsets.UTF_8)
    }

    // ═══════════════════════════════════════════════════════════════
    // CORE C++ ENGINE JNI BINDINGS
    // ═══════════════════════════════════════════════════════════════
    external fun stringFromJNI(): String
    external fun initDatabase(dbPath: String): Boolean
    external fun getAllTracks(): Array<TrackNative>
    external fun searchTracks(query: String): Array<TrackNative>
    external fun insertTrack(filepath: String, title: String, artist: String, album: String, durationSec: Int, bpm: Float): Long
    external fun toggleLike(userId: Int, trackId: Int): Boolean
    external fun getLikedTracks(userId: Int): Array<TrackNative>

    external fun initVectorStore(binPath: String): Boolean
    external fun searchSimilarTracks(trackId: Int, topK: Int): IntArray

    external fun getRecommendations(trackId: Int, recentHistory: IntArray, userId: Int, limit: Int): Array<RecommendationNative>
    external fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
    external fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int)

    external fun initAudioPipeline(modelPath: String): Boolean
    external fun processAudioFile(trackId: Int, filePath: String): Int
    external fun extractBPM(trackId: Int, filePath: String): Float
    external fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean
    external fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean

    external fun setHighPriorityActive(active: Boolean)
    external fun setTotalAiTasks(total: Int)
    external fun getOrchestratorStatus(): OrchestratorStatusNative?
    external fun setBatterySaverActive(active: Boolean)

    external fun upsertStreamedTrack(
        filepath: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
        coverArtPath: String,
        lyricsPath: String,
        bpm: Float,
        key: String
    ): Int
    external fun recordTrackPlay(trackId: Int): Boolean
    external fun getTopPlayedTracks(limit: Int): Array<TrackNative>

    external fun updateSessionVector(trackId: Int, alpha: Float)
    external fun getSessionRecommendations(limit: Int): Array<RecommendationNative>
    external fun getLongTermRecommendations(userId: Int, limit: Int): Array<RecommendationNative>

    external fun processLimiterShorts(buffer: ShortArray, length: Int, threshold: Float, kneeWidth: Float)
    external fun processLimiterFloats(buffer: FloatArray, length: Int, threshold: Float, kneeWidth: Float)

    external fun findFuzzyTrackMatch(title: String, artist: String): Int
    external fun getTracksBatch(offset: Int, limit: Int): Array<TrackNative>

    external fun logEngagementEvent(trackId: Int, durationSec: Int, completionRatio: Float, hourOfDay: Int): Boolean
    external fun getCircadianRecommendations(hourOfDay: Int, limit: Int): Array<RecommendationNative>
    external fun getCircadianSlot(hourOfDay: Int): String

    external fun logHookTelemetry(trackId: Int, favoriteSeekMs: Long, lyricsDwellSec: Int, volumeFlare: Int): Boolean
    external fun recordTrackCooccurrence(trackAId: Int, trackBId: Int): Boolean
    external fun getFavoriteSeekMs(trackId: Int): Long
    external fun getCooccurrenceRecommendations(trackId: Int, limit: Int): IntArray

    const val EVENT_PLAY_START = 1
    const val EVENT_SCRUB_SEEK = 2
    const val EVENT_LYRICS_DWELL = 3
    const val EVENT_VOLUME_CHANGE = 4
    const val EVENT_PLAY_TRANSITION = 5
    external fun pushTelemetryEvent(type: Int, trackId: Long, value: Float)

    external fun getMarkovProbability(fromTrackId: Int, toTrackId: Int): Float
    external fun get2ndOrderMarkovProbability(trackA: Int, trackB: Int, trackC: Int, alpha: Float = 0.1f): Float
    external fun getSatiationPenalty(trackId: Int): Float

    external fun processLufsNormalizerFloats(buffer: FloatArray, length: Int, targetLufs: Float)
    external fun processLufsNormalizerShorts(buffer: ShortArray, length: Int, targetLufs: Float)
    external fun getDynamicTargetLufs(): Float

    external fun generateProofOfCompute(buffer: FloatArray, length: Int, nonce: String): String

    external fun getTargetBpmForTimeSlot(slotOrdinal: Int): Float
    external fun getVectorRecommendations(
        currentTrackId: Int,
        timeWeight: Float,
        deviceWeight: Float,
        bpmTarget: Float,
        limit: Int
    ): Array<RecommendationNative>
    external fun updateTrackEmbedding(trackId: Int, embedding: FloatArray): Boolean
    external fun getTrackEmbedding(trackId: Int): FloatArray?
    external fun cacheSimilarTracks(
        trackId: Int,
        titles: Array<String>,
        artists: Array<String>,
        mbids: Array<String>,
        weights: FloatArray
    ): Boolean

    external fun processPtpTimestamps(t0: Long, t1: Long, t2: Long, t3: Long): Long
    external fun getSynchronizedClockMs(): Long
    external fun getPtpClockOffsetNanos(): Long
    external fun getPtpRttNanos(): Long
    external fun resetPtpState()

    external fun getZhipuKey(index: Int): String
    external fun nukeLocalDatabase(): Boolean

    fun stepAirDropPhysics(stateArray: FloatArray, targetX: Float, targetY: Float, dt: Float) {
        require(stateArray.size >= 13) { "State vector must contain at least 13 floats" }
        val dx = targetX - stateArray[0]
        val dy = targetY - stateArray[1]
        val initialDist = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1.0f)
        stepAirDropPhysics(stateArray, targetX, targetY, initialDist, dt)
    }

    external fun stepAirDropPhysics(
        inOutBuffer: FloatArray,
        targetX: Float,
        targetY: Float,
        initialDist: Float,
        dt: Float
    )

    external fun analyzePcmAcousticDNA(
        directBuffer: java.nio.ByteBuffer,
        byteCount: Int,
        sampleRate: Int,
        channelCount: Int,
        outResults: FloatArray
    ): String

    external fun calculateLyricDrift(
        directPcmBuffer: java.nio.ByteBuffer,
        pcmByteCount: Int,
        textOnsetsMs: LongArray,
        onsetCount: Int,
        sampleRate: Int,
        channelCount: Int
    ): Int

    external fun nativeInitAudioDSP()
    external fun nativeResetAudioDSP()

    /**
     * Audit #22 item 9: configure the native float-DSP singletons (LUFS
     * K-weighting + soft-knee limiter, plus the PCM-tap pair) for the
     * ACTUAL stream geometry. They were pinned at 48 kHz, so 44.1 kHz
     * content (the most common Android rate) measured and limited
     * through the wrong filters. Idempotent; false = nothing changed.
     */
    external fun nativeConfigureAudioDSP(sampleRate: Int, channels: Int): Boolean
    external fun nativeProcessFloatAudio(
        byteBuffer: java.nio.ByteBuffer,
        numFrames: Int,
        channels: Int,
        eqGains: FloatArray? = null
    )

    // ═══════════════════════════════════════════════════════════════
    // RUST CORE ENGINE (JNI / FFI)
    // ═══════════════════════════════════════════════════════════════
    external fun rustFuzzyRankCandidates(query: String, candidatesJson: String): String?
    external fun rustCalculateSimilarity(s1: String, s2: String): Float
    external fun rustCompileToSlyr(lrcContent: String): ByteArray?

    external fun rustParseYouTubePlaylist(jsonBytes: ByteArray): String?
    external fun rustComputeFftSpectrum(pcmFloats: FloatArray, barCount: Int, outBars: FloatArray): Int
    external fun rustProcessEqualizerFrame(pcmFloats: FloatArray, channels: Int, gains: FloatArray?): Int
    external fun rustDownloadStreamDirect(streamUrl: String, destPath: String): String?
    external fun rustScoreAndRankRadioCandidates(
        candidatesJson: String,
        seedBpm: Float,
        seedKey: String,
        seedDurSec: Int,
        seedSig: String,
        queueJson: String
    ): String?
    external fun rustProcessCrossfadePcm(
        outgoingBuf: FloatArray,
        incomingBuf: FloatArray,
        mixedBuf: FloatArray,
        progress: Float
    ): Int
    external fun rustEncryptVaultFile(srcPath: String, destPath: String, masterKey: ByteArray): Int
    external fun rustDecryptVaultFile(srcPath: String, destPath: String, masterKey: ByteArray): Int
    external fun rustParseBackupCsv(csvContent: String): String?
    external fun rustAlignAndCompileLyrics(rawLyrics: String, durationMs: Int, energyFloats: FloatArray?): String?
    external fun rustGenerateNeuroQueue(
        seedJson: String,
        candidatesJson: String,
        brainState: Int,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int
    ): String?

    // ═══════════════════════════════════════════════════════════════
    // PHASE 7: BYZANTINE CONSENSUS & IEEE 1588 PTP JNI EXPORTS
    // ═══════════════════════════════════════════════════════════════

    fun verifyPeerConsensus(
        node1: String, lufs1: Float, key1: String, vec1: FloatArray, proof1: ByteArray,
        node2: String, lufs2: Float, key2: String, vec2: FloatArray, proof2: ByteArray
    ): Boolean {
        require(vec1.size == 128 && vec2.size == 128) { "Vectors must be 128-D" }
        return nativeVerifyPeerConsensus(
            node1, lufs1, key1, vec1, proof1,
            node2, lufs2, key2, vec2, proof2
        )
    }

    fun computePtpOffsetAndDelay(
        seqId: Int, t0: Long, t1: Long, t2: Long, t3: Long, outResultsUs: LongArray
    ) {
        require(outResultsUs.size >= 2) { "Output array must hold at least 2 elements [offset, delay]" }
        nativeCalculatePtp(seqId, t0, t1, t2, t3, outResultsUs)
    }

    fun pinThreadToLittleCores(): Boolean {
        return try {
            nativePinThreadToLittleCores()
        } catch (_: Throwable) {
            false
        }
    }

    private external fun nativePinThreadToLittleCores(): Boolean

    private external fun nativeVerifyPeerConsensus(
        node1: String, lufs1: Float, key1: String, vec1: FloatArray, proof1: ByteArray,
        node2: String, lufs2: Float, key2: String, vec2: FloatArray, proof2: ByteArray
    ): Boolean

    private external fun nativeCalculatePtp(
        seqId: Int, t0: Long, t1: Long, t2: Long, t3: Long, outResults: LongArray
    )

    // ═══════════════════════════════════════════════════════════════
    // ZERO-SERVER JAM MESH v4 — FROZEN JNI ABI + KOTLIN TRANSPORT SEAM
    // ═══════════════════════════════════════════════════════════════
    //
    // Contract owners (cross-team frozen ABI — names + signatures locked):
    //   • Engineer 1 implements the C++ symbols in libstreamify_native_core:
    //     PTP clock discipline + Sinc resampler + DAC playout-delay probe.
    //   • Engineer 2 implements the Rust symbols in libstreamify_core_rs:
    //     the leaderless P2P mesh (Wi-Fi Direct / 5 GHz LAN UDP / WebRTC).
    //   • The Kotlin side MUST NOT rename or re-shape these declarations.
    //
    // Safe wrappers below follow this file's long-standing convention:
    // every call site goes through a `try { … } catch (Throwable)` wrapper so
    // a device without the native artifact (JVM test shard, x86 emulator,
    // skipRust CI builds) degrades to documented fallbacks instead of
    // crashing the Jam stack. Wrappers delegate 1:1 to the frozen symbols.
    //
    // WIRE-TYPE ENVELOPE (audit #44 P0-1): the Rust mesh's frozen type
    // registry owns 0x01..0x11 as protocol traffic (PTP sync, CRDT ops,
    // heartbeats, gossip control, chunk swarm, governance …) and the JNI
    // guard rightly rejects app attempts to spoof them. JamWire's own
    // vocabulary (Msg 1..=31) collides with that range head-on, so the
    // native egress below folds the whole JamWire family into the single
    // app-visible envelope type 0x20 — the JamWire header inside the
    // payload self-describes the real sub-type, so nothing is lost. The
    // meshOverride (test mock) keeps receiving raw JamWire types: its
    // assertions are app-layer contracts, and ingress ignores the mesh
    // type (JamWire.decode validates the payload itself).
    private const val MESH_JAM_APP_TYPE = 0x20

    /** JamWire-family (1..=31) → mesh envelope 0x20; anything else passes
     * through untouched (future app extensions ≥ 0x20 are already legal). */
    private fun meshWireType(msgType: Int): Int =
        if (msgType in 1..31) MESH_JAM_APP_TYPE else msgType

    /**
     * Upcall surface the Rust mesh invokes when frames / peer lifecycle
     * events arrive. Registered via [nativeP2pRegisterIncomingCallback].
     * Default (empty) methods keep the surface forward-compatible.
     */
    interface P2pIncomingListener {
        /** Raw frame landed from peer [peerIdHex] (mesh-level identity). */
        fun onP2pFrame(peerIdHex: String, msgType: Int, payload: ByteArray) {}

        /** A peer completed mesh-level connection (handshake done). */
        fun onP2pPeerJoined(peerIdHex: String) {}

        /** A peer dropped (battery death, Wi-Fi loss, graceful leave). */
        fun onP2pPeerLeft(peerIdHex: String) {}
    }

    /** Mesh-frame event fanned out on [incomingFrames]. */
    data class P2pFrameEvent(
        val peerIdHex: String,
        val msgType: Int,
        val payload: ByteArray
    )

    /** Peer lifecycle event fanned out on [peerEvents]. */
    data class P2pPeerEvent(
        val peerIdHex: String,
        val joined: Boolean
    )

    /**
     * Test/loopback seam for the mesh transport. When non-null every P2P
     * wrapper routes here instead of JNI — this is the injection point used
     * by MockNativeBridge's virtual room (multi-device frame routing,
     * host-drop-and-failover, clock-jitter injection).
     */
    interface P2pMesh {
        fun start(sessionId: String, deviceId: String, enableLan: Boolean, enableWebRtc: Boolean): Boolean
        fun stop()
        fun broadcast(msgType: Int, payload: ByteArray): Boolean
        fun sendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean
        fun connectedPeerCount(): Int
        fun registerIncoming(listener: P2pIncomingListener): Boolean
    }

    @Volatile
    var meshOverride: P2pMesh? = null

    /** Test hook: overrides the synced nanosecond clock (virtual time). */
    @Volatile
    var syncedClockOverride: (() -> Long)? = null

    /**
     * Test hook: overrides the canonical cad-id derivation (hermetic JVM
     * shard — no .so, so the native hash is unavailable and every track
     * would collapse to cad 0, refusing OP_ADD before the CRDT seam).
     */
    @Volatile
    var jamCadIdOverride: ((String, String, Int) -> Long)? = null

    private val _incomingFrames = kotlinx.coroutines.flow.MutableSharedFlow<P2pFrameEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Raw incoming mesh frames — collected by the JamEngine dispatcher. */
    val incomingFrames: kotlinx.coroutines.flow.SharedFlow<P2pFrameEvent> = _incomingFrames

    private val _peerEvents = kotlinx.coroutines.flow.MutableSharedFlow<P2pPeerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Mesh peer join/leave lifecycle — drives Merkle reconciliation. */
    val peerEvents: kotlinx.coroutines.flow.SharedFlow<P2pPeerEvent> = _peerEvents

    /** Internal listener bridging native upcalls into the coroutine flows. */
    private val meshFlowListener = object : P2pIncomingListener {
        override fun onP2pFrame(peerIdHex: String, msgType: Int, payload: ByteArray) {
            _incomingFrames.tryEmit(P2pFrameEvent(peerIdHex, msgType, payload))
        }

        override fun onP2pPeerJoined(peerIdHex: String) {
            _peerEvents.tryEmit(P2pPeerEvent(peerIdHex, joined = true))
        }

        override fun onP2pPeerLeft(peerIdHex: String) {
            _peerEvents.tryEmit(P2pPeerEvent(peerIdHex, joined = false))
        }
    }

    // ── FROZEN ABI: Engineer 1 — C++ DSP & Clock (libstreamify_native_core) ──

    @JvmStatic
    external fun nativePtpProcessTimestamps(t0: Long, t1: Long, t2: Long, t3: Long): Long

    @JvmStatic
    external fun nativeGetSynchronizedClockNanos(): Long

    @JvmStatic
    external fun nativePtpReset()

    @JvmStatic
    external fun nativeResamplerSetTargetDriftNanos(driftNanos: Long)

    @JvmStatic
    external fun nativeResamplerProcessBuffer(
        input: java.nio.ByteBuffer, output: java.nio.ByteBuffer,
        frameCount: Int, channels: Int, sampleRate: Int
    ): Int

    @JvmStatic
    external fun nativeGetHardwarePlayoutDelayNanos(): Long

    // ── FROZEN ABI: Engineer 2 — Rust P2P Mesh (libstreamify_core_rs) ───────

    @JvmStatic
    external fun nativeP2pStart(
        sessionId: String, deviceId: String, enableLan: Boolean, enableWebRtc: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeP2pStop()

    @JvmStatic
    external fun nativeP2pBroadcast(msgType: Int, payload: ByteArray): Boolean

    @JvmStatic
    external fun nativeP2pSendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean

    @JvmStatic
    external fun nativeP2pGetConnectedPeerCount(): Int

    /**
     * Registers the JVM upcall listener the Rust mesh retains globally.
     * (Referenced by the mission ABI §A — the incoming-frame conduit.)
     */
    @JvmStatic
    external fun nativeP2pRegisterIncomingCallback(listener: P2pIncomingListener): Boolean

    // ── Safe wrappers (see conventions at the top of this section) ──────────

    /** PTP offset estimate in nanos; 0 when native clock is unavailable. */
    fun ptpProcessTimestampsNanos(t0: Long, t1: Long, t2: Long, t3: Long): Long = try {
        nativePtpProcessTimestamps(t0, t1, t2, t3)
    } catch (_: Throwable) {
        0L
    }

    /**
     * THE atomic room clock (nanoseconds). Falls back to the raw local
     * monotonic clock when the native PTP discipline is unavailable — all
     * room math stays in one domain either way.
     */
    fun synchronizedClockNanos(): Long {
        syncedClockOverride?.let { return it() }
        return try {
            nativeGetSynchronizedClockNanos()
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }

    fun ptpReset() {
        try { nativePtpReset() } catch (_: Throwable) {}
    }

    /** Feeds the measured drift (nanos) to the native Sinc resampler. */
    fun resamplerSetTargetDriftNanos(driftNanos: Long) {
        try { nativeResamplerSetTargetDriftNanos(driftNanos) } catch (_: Throwable) {}
    }

    /**
     * Streams one PCM buffer through the C++ Sinc resampler. Returns frames
     * produced, or -1 when the native core is unavailable (caller must use
     * its Kotlin fallback path).
     */
    fun resamplerProcessBuffer(
        input: java.nio.ByteBuffer, output: java.nio.ByteBuffer,
        frameCount: Int, channels: Int, sampleRate: Int
    ): Int = try {
        nativeResamplerProcessBuffer(input, output, frameCount, channels, sampleRate)
    } catch (_: Throwable) {
        -1
    }

    /** DAC / Bluetooth A2DP playout delay in nanos; -1 = unknown. */
    fun hardwarePlayoutDelayNanos(): Long = try {
        nativeGetHardwarePlayoutDelayNanos()
    } catch (_: Throwable) {
        -1L
    }

    /** Boots the Rust mesh for this room; false = transport unavailable. */
    fun p2pStart(
        sessionId: String, deviceId: String, enableLan: Boolean, enableWebRtc: Boolean
    ): Boolean {
        // Both transports (native Rust mesh AND the test mock override) must
        // bridge their upcalls into [incomingFrames] — the early-return below
        // previously left the mock unregistered, killing ingress in the JVM
        // test shard exactly like a silent radio.
        meshOverride?.let {
            val ok = it.start(sessionId, deviceId, enableLan, enableWebRtc)
            if (ok) runCatching { it.registerIncoming(meshFlowListener) }
            return ok
        }
        return try {
            val ok = nativeP2pStart(sessionId, deviceId, enableLan, enableWebRtc)
            if (ok) try { nativeP2pRegisterIncomingCallback(meshFlowListener) } catch (_: Throwable) {}
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun p2pStop() {
        meshOverride?.let { return it.stop() }
        try { nativeP2pStop() } catch (_: Throwable) {}
    }

    /** Fan-out broadcast on the mesh; false = transport down (caller retries). */
    fun p2pBroadcast(msgType: Int, payload: ByteArray): Boolean {
        meshOverride?.let { return it.broadcast(msgType, payload) }
        return try {
            nativeP2pBroadcast(meshWireType(msgType), payload)
        } catch (_: Throwable) {
            false
        }
    }

    fun p2pSendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean {
        meshOverride?.let { return it.sendToPeer(peerIdHex, msgType, payload) }
        return try {
            nativeP2pSendToPeer(peerIdHex, meshWireType(msgType), payload)
        } catch (_: Throwable) {
            false
        }
    }

    fun p2pConnectedPeerCount(): Int {
        meshOverride?.let { return it.connectedPeerCount() }
        return try {
            nativeP2pGetConnectedPeerCount()
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Registers the flow-bridging listener with the mesh (native or
     * override). The JamEngine transport loop calls this once on start.
     */
    fun p2pRegisterIncoming(listener: P2pIncomingListener): Boolean {
        meshOverride?.let { return it.registerIncoming(listener) }
        return try {
            nativeP2pRegisterIncomingCallback(listener)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Test injection: feed a raw frame into [incomingFrames] exactly as the
     * native upcall would. MockNativeBridge drives the engine's dispatcher
     * through this gate so production and test share one code path.
     */
    fun simulateIncomingFrame(peerIdHex: String, msgType: Int, payload: ByteArray) {
        meshFlowListener.onP2pFrame(peerIdHex, msgType, payload)
    }

    /** Test injection: synthesize mesh peer lifecycle events. */
    fun simulatePeerEvent(peerIdHex: String, joined: Boolean) {
        if (joined) meshFlowListener.onP2pPeerJoined(peerIdHex) else meshFlowListener.onP2pPeerLeft(peerIdHex)
    }
}

