package com.streamify.app.data.discovery

import com.streamify.app.data.models.Track
import com.streamify.app.util.SLog
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TasteLane — the WS-D "Taste Engine" client lane (AHEAD.md Wave 3).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The private, fully offline, explainable recommendation surface over the
 * UniFFI `TasteEngine` object (rust: `streamify-contract/src/taste_engine.rs`):
 * the neuro-acoustic radio queue with D4 reason strings, the circadian
 * Daylist v2 lane, and the D5 group blend with "N others like this" badges.
 *
 * Mirrors [com.streamify.app.data.uniffi.StreamifyEngineBridge]'s backend
 * seam: the raw generated UniFFI proxies (package `uniffi.streamify`) never
 * leak past this object. Callers get Kotlin-native DTOs, [Result]-shaped
 * semantics that degrade to empty outputs when the native lane is absent
 * (JVM unit tests, stubbed devices), and a swappable [TasteBackend] for
 * hermetic tests.
 *
 * YouTube remains the ONLINE "Main Brain" (ContinuumRadioEngine crawls it);
 * this lane is the local intelligence that takes over offline and during
 * peer-to-peer Blend Jams.
 */
object TasteLane {

    enum class Availability { READY, NATIVE_UNAVAILABLE, MOCK }

    /** The 5 neuro-acoustic brain states (mirrors the Rust `BrainState`). */
    enum class BrainStateKt { FLOW, DISTRESS, HYPNOSIS, IMPATIENCE, OBSESSION }

    /**
     * One candidate/seed track in the taste lane's vocabulary — the Kotlin
     * mirror of the UDL `NeuroCandidate` record. `excludedFromTaste` (D7)
     * is filtered at SCORE time on the engine side, never by the caller.
     */
    data class TasteTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String = "",
        @Suppress("ktlint:standard:property-naming") val source: Int = 0, // 0=Liked 1=Spotify 2=YouTube
        val bpm: Float = 120f,
        val key: String = "8A",
        val energy: Float = 0.5f,
        val valence: Float = 0.5f,
        val acousticSim: Float = 0.5f,
        val userAffinity: Float = 0.5f,
        val lastPlayedSec: Long = 0L,
        val excludedFromTaste: Boolean = false
    )

    /** The feature attributions behind one row (D4 — badge-renderable). */
    data class TasteBreakdown(
        val base: Float,
        val satiationPenalty: Float,
        val harmonicBonus: Float,
        val circadianBonus: Float,
        val tempoDriftPenalty: Float,
        val bpmDistance: Float,
        val camelotDistance: Int,
        val freshness: Float
    )

    /** One recommended row: the candidate, score, attributions and reason. */
    data class TasteRow(
        val track: TasteTrack,
        val score: Float,
        val breakdown: TasteBreakdown?,
        val reason: String
    )

    /** One circadian daypart's taste vector (D6). */
    data class DaypartVectorKt(
        val bpmTarget: Float,
        val energyTarget: Float,
        val brightnessTarget: Float,
        val freshnessBias: Float
    )

    /** The 4-slot circadian profile (D6), built from LOCAL history. */
    data class CircadianProfileKt(
        val morning: DaypartVectorKt,
        val afternoon: DaypartVectorKt,
        val evening: DaypartVectorKt,
        val lateNight: DaypartVectorKt
    ) {
        companion object {
            /** The classic defaults (the engine's `classic_circadian_profile`). */
            fun classic(): CircadianProfileKt = CircadianProfileKt(
                morning = DaypartVectorKt(126f, 0.62f, 0.60f, 0.45f),
                afternoon = DaypartVectorKt(124f, 0.75f, 0.62f, 0.50f),
                evening = DaypartVectorKt(116f, 0.48f, 0.50f, 0.50f),
                lateNight = DaypartVectorKt(100f, 0.35f, 0.38f, 0.55f)
            )
        }
    }

    /** One blend member's taste seed (D5). */
    data class BlendSeedKt(
        val memberId: String,
        val artists: List<String>,
        val genres: List<String>,
        val recentTracks: List<Pair<String, String>> = emptyList() // title to artist
    )

    /** One candidate in the group blend pool (D5). */
    data class BlendTrackKt(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String = "",
        val durationSec: Int = 0,
        val bpm: Float = 0f,
        val key: String = "8A",
        val genres: List<String> = emptyList(),
        val addedAtMs: Long = 0L,
        val coOccurrenceCount: Int = 0,
        val excludedFromTaste: Boolean = false
    )

    /** One ranked blend row: score decomposition + the D4 reason + roster. */
    data class BlendRowKt(
        val id: Long,
        val title: String,
        val artist: String,
        val score: Float,
        val overlapMembers: Int,
        val mutualBlend: Boolean,
        val memberMatches: List<String>,
        val reason: String
    ) {
        /** The roster badge count: the local viewer is one of the matches. */
        val othersCount: Int get() = (overlapMembers - 1).coerceAtLeast(0)
    }

    /** The swappable engine seam (production native vs hermetic mock). */
    interface TasteBackend {
        val availability: Availability
        fun generateRadioQueue(
            seed: TasteTrack,
            candidates: List<TasteTrack>,
            state: BrainStateKt,
            nowSec: Long,
            hourOfDay: Int,
            targetCount: Int,
            profile: CircadianProfileKt
        ): List<TasteRow>

        fun generateDaylist(
            candidates: List<TasteTrack>,
            nowSec: Long,
            hourOfDay: Int,
            targetCount: Int,
            profile: CircadianProfileKt
        ): List<TasteRow>

        fun scoreGroupBlend(
            candidates: List<BlendTrackKt>,
            seeds: List<BlendSeedKt>,
            nowMs: Long
        ): List<BlendRowKt>
    }

    // ── Backend lifecycle ──────────────────────────────────────────────────

    private val backendRef = AtomicReference<TasteBackend?>(null)

    /** Live availability of the taste lane (native or mock). */
    val availability: Availability
        get() = backend().availability

    private fun backend(): TasteBackend {
        val current = backendRef.get()
        if (current != null) return current
        val native = try {
            NativeTasteBackend()
        } catch (t: Throwable) {
            // The generated proxies are not on the classpath or the
            // engine .so is not linkable — degrade, never crash.
            SLog.w("TasteLane", "native taste engine unavailable: ${t.message}")
            DegradedTasteBackend
        }
        backendRef.set(native)
        return native
    }

    /** Installs a hermetic backend (JVM unit tests). */
    fun installBackend(mock: TasteBackend) {
        backendRef.set(mock)
    }

    /** Restores the production native backend (test cleanup). */
    fun reset() {
        backendRef.set(null)
    }

    // ── The lane operations (all degrade to empty on native absence) ──────

    /**
     * D3/D4 — the radio queue through the unified engine lane: tri-engine
     * brain-state weighting, 3.5 h satiation decay, Camelot harmony, the
     * 4-slot circadian profile and the 5-slot cinematic micro-arc, every
     * row carrying its D4 reason.
     */
    fun generateRadioQueue(
        seed: TasteTrack,
        candidates: List<TasteTrack>,
        state: BrainStateKt,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: CircadianProfileKt = CircadianProfileKt.classic()
    ): List<TasteRow> = try {
        backend().generateRadioQueue(seed, candidates, state, nowSec, hourOfDay, targetCount, profile)
    } catch (t: Throwable) {
        SLog.st("TasteLane", "generateRadioQueue degraded", t)
        emptyList()
    }

    /** D6 — the Daylist v2 lane: circadian, local-first shelf ranking. */
    fun generateDaylist(
        candidates: List<TasteTrack>,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: CircadianProfileKt = CircadianProfileKt.classic()
    ): List<TasteRow> = try {
        backend().generateDaylist(candidates, nowSec, hourOfDay, targetCount, profile)
    } catch (t: Throwable) {
        SLog.st("TasteLane", "generateDaylist degraded", t)
        emptyList()
    }

    /** D5/D4 — the group blend re-rank with badge-ready rosters + reasons. */
    fun scoreGroupBlend(
        candidates: List<BlendTrackKt>,
        seeds: List<BlendSeedKt>,
        nowMs: Long
    ): List<BlendRowKt> = try {
        backend().scoreGroupBlend(candidates, seeds, nowMs)
    } catch (t: Throwable) {
        SLog.st("TasteLane", "scoreGroupBlend degraded", t)
        emptyList()
    }

    /** The D2 embedding producer over canonical 48 kHz stereo PCM (LE bytes). */
    suspend fun computeAcousticEmbedding(interleavedStereoF32Le: ByteArray): String? =
        withContext(Dispatchers.Default) {
            try {
                val native = backend() as? NativeTasteBackend
                    ?: return@withContext null
                native.computeAcousticEmbedding(interleavedStereoF32Le)?.let { e ->
                    val letter = if (e.camelotLetter == 0u) "A" else "B"
                    "v${e.version} bpm=${e.bpm.toInt()} " +
                        "key=${e.camelotNumber}$letter lufs=${e.integratedLufs}"
                }
            } catch (t: Throwable) {
                SLog.st("TasteLane", "computeAcousticEmbedding degraded", t)
                null
            }
        }
}

// ── Top-level mapping helpers ─────────────────────────────────────────────

/**
 * Map the app [Track] model onto the lane's candidate vocabulary,
 * consulting the taste-exclusion guard (D7) at the boundary so the
 * engine filters at score time.
 */
fun Track.toTasteTrack(
    source: Int,
    guard: TasteExclusionIndex? = null,
    lastPlayedSec: Long = 0L
): TasteLane.TasteTrack = TasteLane.TasteTrack(
    id = "t_$id",
    title = title,
    artist = artist,
    album = album,
    source = source,
    bpm = if (bpm > 0f) bpm else 120f,
    key = key.ifBlank { "8A" },
    energy = 0.7f,
    valence = 0.6f,
    acousticSim = 0.8f,
    userAffinity = if (isLiked) 0.9f else 0.5f,
    lastPlayedSec = lastPlayedSec,
    excludedFromTaste = guard?.isTrackExcluded(this) ?: false
)

/** Map a ranked row back onto the playable [Track] model with its reason. */
fun TasteLane.TasteRow.toTrack(sourceTag: String = "taste_engine"): Track = Track(
    id = track.title.hashCode() + track.artist.hashCode(),
    title = track.title,
    artist = track.artist,
    album = track.album.ifBlank { "Streamify Radio" },
    bpm = track.bpm,
    key = track.key,
    source = sourceTag,
    reason = reason
)

/** The native UniFFI backend — the ONLY place `uniffi.streamify` leaks into. */
private class NativeTasteBackend : TasteLane.TasteBackend {

    private val engine: uniffi.streamify.TasteEngine

    init {
        // Class-load + native-link probe: a missing bindgen artifact or
        // unlinked .so fails HERE, flipping the lane to degraded.
        engine = uniffi.streamify.TasteEngine()
    }

    override val availability: TasteLane.Availability
        get() = TasteLane.Availability.READY

    private fun mapState(state: TasteLane.BrainStateKt) = when (state) {
        TasteLane.BrainStateKt.FLOW -> uniffi.streamify.BrainState.FLOW
        TasteLane.BrainStateKt.DISTRESS -> uniffi.streamify.BrainState.DISTRESS
        TasteLane.BrainStateKt.HYPNOSIS -> uniffi.streamify.BrainState.HYPNOSIS
        TasteLane.BrainStateKt.IMPATIENCE -> uniffi.streamify.BrainState.IMPATIENCE
        TasteLane.BrainStateKt.OBSESSION -> uniffi.streamify.BrainState.OBSESSION
    }

    private fun mapVector(v: TasteLane.DaypartVectorKt) = uniffi.streamify.DaypartVector(
        bpmTarget = v.bpmTarget,
        energyTarget = v.energyTarget,
        brightnessTarget = v.brightnessTarget,
        freshnessBias = v.freshnessBias
    )

    private fun mapProfile(p: TasteLane.CircadianProfileKt) = uniffi.streamify.CircadianProfile(
        morning = mapVector(p.morning),
        afternoon = mapVector(p.afternoon),
        evening = mapVector(p.evening),
        lateNight = mapVector(p.lateNight)
    )

    private fun mapTrack(t: TasteLane.TasteTrack) = uniffi.streamify.NeuroCandidate(
        id = t.id,
        title = t.title,
        artist = t.artist,
        album = t.album,
        source = t.source.toUByte(),
        bpm = t.bpm,
        key = t.key,
        energy = t.energy,
        valence = t.valence,
        acousticSim = t.acousticSim,
        userAffinity = t.userAffinity,
        lastPlayedSec = t.lastPlayedSec.toULong(),
        excludedFromTaste = t.excludedFromTaste
    )

    private fun mapRow(r: uniffi.streamify.NeuroQueueRow) = TasteLane.TasteRow(
        track = TasteLane.TasteTrack(
            id = r.candidate.id,
            title = r.candidate.title,
            artist = r.candidate.artist,
            album = r.candidate.album,
            source = r.candidate.source.toInt(),
            bpm = r.candidate.bpm,
            key = r.candidate.key,
            energy = r.candidate.energy,
            valence = r.candidate.valence,
            acousticSim = r.candidate.acousticSim,
            userAffinity = r.candidate.userAffinity,
            lastPlayedSec = r.candidate.lastPlayedSec.toLong(),
            excludedFromTaste = r.candidate.excludedFromTaste
        ),
        score = r.score,
        breakdown = TasteLane.TasteBreakdown(
            base = r.breakdown.base,
            satiationPenalty = r.breakdown.satiationPenalty,
            harmonicBonus = r.breakdown.harmonicBonus,
            circadianBonus = r.breakdown.circadianBonus,
            tempoDriftPenalty = r.breakdown.tempoDriftPenalty,
            bpmDistance = r.breakdown.bpmDistance,
            camelotDistance = r.breakdown.camelotDistance,
            freshness = r.breakdown.freshness
        ),
        reason = r.reason
    )

    override fun generateRadioQueue(
        seed: TasteLane.TasteTrack,
        candidates: List<TasteLane.TasteTrack>,
        state: TasteLane.BrainStateKt,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: TasteLane.CircadianProfileKt
    ): List<TasteLane.TasteRow> = engine.generateRadioQueue(
        seed = mapTrack(seed),
        candidates = candidates.map { mapTrack(it) },
        brainState = mapState(state),
        nowSec = nowSec.toULong(),
        hourOfDay = hourOfDay.toUInt(),
        targetCount = targetCount.toUInt(),
        profile = mapProfile(profile)
    ).map { mapRow(it) }

    override fun generateDaylist(
        candidates: List<TasteLane.TasteTrack>,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: TasteLane.CircadianProfileKt
    ): List<TasteLane.TasteRow> = engine.generateDaylist(
        candidates = candidates.map { mapTrack(it) },
        nowSec = nowSec.toULong(),
        hourOfDay = hourOfDay.toUInt(),
        targetCount = targetCount.toUInt(),
        profile = mapProfile(profile)
    ).map { mapRow(it) }

    override fun scoreGroupBlend(
        candidates: List<TasteLane.BlendTrackKt>,
        seeds: List<TasteLane.BlendSeedKt>,
        nowMs: Long
    ): List<TasteLane.BlendRowKt> {
        val result = engine.scoreGroupBlend(
            candidates = candidates.map { c ->
                uniffi.streamify.BlendCandidate(
                    id = c.id,
                    title = c.title,
                    artist = c.artist,
                    album = c.album,
                    durationSec = c.durationSec,
                    bpm = c.bpm,
                    key = c.key,
                    genres = c.genres,
                    addedAtMs = c.addedAtMs,
                    coOccurrenceCount = c.coOccurrenceCount.toUInt(),
                    excludedFromTaste = c.excludedFromTaste
                )
            },
            seeds = seeds.map { s ->
                uniffi.streamify.BlendMemberSeed(
                    memberId = s.memberId,
                    artists = s.artists,
                    genres = s.genres,
                    recentTracks = s.recentTracks.map { (title, artist) ->
                        uniffi.streamify.BlendRecentTrack(title = title, artist = artist)
                    }
                )
            },
            nowMs = nowMs
        )
        return result.ranked.map { r ->
            TasteLane.BlendRowKt(
                id = r.id,
                title = r.title,
                artist = r.artist,
                score = r.score,
                overlapMembers = r.overlapMembers.toInt(),
                mutualBlend = r.mutualBlend,
                memberMatches = r.memberMatches,
                reason = r.reason
            )
        }
    }

    /** D2: the raw embedding record (headline scalars). */
    fun computeAcousticEmbedding(interleavedStereoF32Le: ByteArray): uniffi.streamify.AcousticEmbedding? =
        engine.computeAcousticEmbedding(interleavedStereoF32Le)
}

/** The degraded fallback: every lane operation yields empty results. */
private object DegradedTasteBackend : TasteLane.TasteBackend {
    override val availability: TasteLane.Availability
        get() = TasteLane.Availability.NATIVE_UNAVAILABLE

    override fun generateRadioQueue(
        seed: TasteLane.TasteTrack,
        candidates: List<TasteLane.TasteTrack>,
        state: TasteLane.BrainStateKt,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: TasteLane.CircadianProfileKt
    ): List<TasteLane.TasteRow> = emptyList()

    override fun generateDaylist(
        candidates: List<TasteLane.TasteTrack>,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: TasteLane.CircadianProfileKt
    ): List<TasteLane.TasteRow> = emptyList()

    override fun scoreGroupBlend(
        candidates: List<TasteLane.BlendTrackKt>,
        seeds: List<TasteLane.BlendSeedKt>,
        nowMs: Long
    ): List<TasteLane.BlendRowKt> = emptyList()
}
