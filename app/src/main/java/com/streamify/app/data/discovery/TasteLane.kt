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
 * seam — including its REFLECTION dispatch onto the generated
 * `uniffi.streamify` proxies: every environment that compiles without the
 * generated sources (hermetic JVM shards, the CodeQL tracing build —
 * `generateUniffiKotlinBindings` skipped ⇒ `uniffi.*` classes never exist
 * at compile time) keeps an identical binary. Real builds light the
 * dispatch up with zero further app changes.
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

    /** The D2 embedding headline scalars (see the v1 schema doc). */
    data class EmbeddingSummary(
        val version: Int,
        val bpm: Float,
        val camelotNumber: Int,
        val camelotLetter: Int,
        val integratedLufs: Float,
        val danceability: Float
    ) {
        /** Human-readable one-liner (the ingest log line). */
        override fun toString(): String {
            val letter = if (camelotLetter == 0) "A" else "B"
            return "v$version bpm=${bpm.toInt()} key=$camelotNumber$letter " +
                "lufs=${"%.1f".format(integratedLufs)} dance=${"%.2f".format(danceability)}"
        }
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

        /** D2: run the embedding producer (null when unsupported). */
        fun computeAcousticEmbedding(interleavedStereoF32Le: ByteArray): EmbeddingSummary? =
            null
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
            NativeTasteBackend.create()
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

    /** D2: the embedding producer over canonical 48 kHz stereo PCM (LE bytes). */
    suspend fun computeAcousticEmbedding(interleavedStereoF32Le: ByteArray): EmbeddingSummary? =
        withContext(Dispatchers.Default) {
            try {
                backend().computeAcousticEmbedding(interleavedStereoF32Le)
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

// ── The reflective native backend ─────────────────────────────────────────

/**
 * The native UniFFI backend — the ONLY place the generated
 * `uniffi.streamify` proxies are touched, and only via REFLECTION (the
 * StreamifyEngineBridge seam: environments that compile without the
 * bindgen output keep an identical binary). Kotlin unsigned params
 * (`UByte`/`UInt`/`ULong`) erase to `byte`/`int`/`long` in the JVM
 * signatures — [Reflect.adapt] bridges the values, and record fields are
 * read through their public getters/backing fields.
 */
private class NativeTasteBackend private constructor(
    private val engine: Any?,
    private val reflect: Reflect?
) : TasteLane.TasteBackend {

    companion object {
        /** Generated proxy class for the UDL `TasteEngine` interface. */
        private const val PROXY_CLASS = "uniffi.streamify.TasteEngine"

        fun create(): NativeTasteBackend = try {
            val cls = Class.forName(PROXY_CLASS)
            val engine = cls.getDeclaredConstructor().newInstance()
            NativeTasteBackend(engine, Reflect(cls))
        } catch (_: Throwable) {
            NativeTasteBackend(null, null)
        }
    }

    override val availability: TasteLane.Availability
        get() = if (engine != null && reflect != null) TasteLane.Availability.READY
        else TasteLane.Availability.NATIVE_UNAVAILABLE

    private fun <T> ready(op: String, body: (Any, Reflect) -> T): T? {
        val e = engine ?: return degraded(op)
        val r = reflect ?: return degraded(op)
        return body(e, r)
    }

    private fun <T> degraded(op: String): T? {
        SLog.w("TasteLane", "$op: native taste engine not wired")
        return null
    }

    override fun generateRadioQueue(
        seed: TasteLane.TasteTrack,
        candidates: List<TasteLane.TasteTrack>,
        state: TasteLane.BrainStateKt,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: TasteLane.CircadianProfileKt
    ): List<TasteLane.TasteRow> = ready("generateRadioQueue") { e, r ->
        val raw = r.generateRadioQueue.invoke(
            e,
            r.buildNeuroCandidate(seed),
            candidates.map { r.buildNeuroCandidate(it) },
            r.brainState(state),
            nowSec,
            hourOfDay,
            targetCount,
            r.buildProfile(profile)
        ) as List<*>
        raw.map { r.readNeuroRow(it!!) }
    } ?: emptyList()

    override fun generateDaylist(
        candidates: List<TasteLane.TasteTrack>,
        nowSec: Long,
        hourOfDay: Int,
        targetCount: Int,
        profile: TasteLane.CircadianProfileKt
    ): List<TasteLane.TasteRow> = ready("generateDaylist") { e, r ->
        val raw = r.generateDaylist.invoke(
            e,
            candidates.map { r.buildNeuroCandidate(it) },
            nowSec,
            hourOfDay,
            targetCount,
            r.buildProfile(profile)
        ) as List<*>
        raw.map { r.readNeuroRow(it!!) }
    } ?: emptyList()

    override fun scoreGroupBlend(
        candidates: List<TasteLane.BlendTrackKt>,
        seeds: List<TasteLane.BlendSeedKt>,
        nowMs: Long
    ): List<TasteLane.BlendRowKt> = ready("scoreGroupBlend") { e, r ->
        val raw = r.scoreGroupBlend.invoke(
            e,
            candidates.map { r.buildBlendCandidate(it) },
            seeds.map { r.buildBlendSeed(it) },
            nowMs
        )
        val ranked = Reflect.field(raw, "ranked") as List<*>
        ranked.map { row ->
            TasteLane.BlendRowKt(
                id = (Reflect.field(row!!, "id") as Number).toLong(),
                title = Reflect.field(row, "title") as String,
                artist = Reflect.field(row, "artist") as String,
                score = (Reflect.field(row, "score") as Number).toFloat(),
                overlapMembers = (Reflect.field(row, "overlapMembers") as Number).toInt(),
                mutualBlend = Reflect.field(row, "mutualBlend") as Boolean,
                memberMatches = (Reflect.field(row, "memberMatches") as List<*>)
                    .mapNotNull { it?.toString() },
                reason = (Reflect.field(row, "reason") as? String).orEmpty()
            )
        }
    } ?: emptyList()

    override fun computeAcousticEmbedding(interleavedStereoF32Le: ByteArray): TasteLane.EmbeddingSummary? =
        ready("computeAcousticEmbedding") { e, r ->
            val raw = r.computeEmbedding.invoke(e, interleavedStereoF32Le)
            TasteLane.EmbeddingSummary(
                version = (Reflect.field(raw, "version") as Number).toInt(),
                bpm = (Reflect.field(raw, "bpm") as Number).toFloat(),
                camelotNumber = (Reflect.field(raw, "camelotNumber") as Number).toInt(),
                camelotLetter = (Reflect.field(raw, "camelotLetter") as Number).toInt(),
                integratedLufs = (Reflect.field(raw, "integratedLufs") as Number).toFloat(),
                danceability = (Reflect.field(raw, "danceability") as Number).toFloat()
            )
        }

    /** One resolved method set + the record builders/readers. */
    private class Reflect(cls: Class<*>) {
        private val neuroCandidateClass = Class.forName("uniffi.streamify.NeuroCandidate")
        private val brainStateClass = Class.forName("uniffi.streamify.BrainState")
        private val daypartClass = Class.forName("uniffi.streamify.DaypartVector")
        private val profileClass = Class.forName("uniffi.streamify.CircadianProfile")
        private val blendCandidateClass = Class.forName("uniffi.streamify.BlendCandidate")
        private val blendSeedClass = Class.forName("uniffi.streamify.BlendMemberSeed")
        private val blendRecentClass = Class.forName("uniffi.streamify.BlendRecentTrack")

        // Kotlin mangles the JVM names of functions whose signatures
        // carry value classes (UByte/UInt/ULong -> `name-<hash>`), so
        // methods resolve by name PREFIX + arity — tolerant to both
        // the mangled and unmangled shapes across Kotlin versions.
        val generateRadioQueue = findMethod(cls, "generateRadioQueue", 7)
        val generateDaylist = findMethod(cls, "generateDaylist", 5)
        val scoreGroupBlend = findMethod(cls, "scoreGroupBlend", 3)
        val computeEmbedding = findMethod(cls, "computeAcousticEmbedding", 1)

        /** UDL `u64`/`u32` params erase to long/int in the JVM signature. */
        fun brainState(state: TasteLane.BrainStateKt): Any =
            brainStateClass.enumConstants.first { it.toString() == state.name }

        fun buildNeuroCandidate(t: TasteLane.TasteTrack): Any = construct(
            neuroCandidateClass,
            t.id, t.title, t.artist, t.album,
            t.source, t.bpm, t.key, t.energy, t.valence,
            t.acousticSim, t.userAffinity, t.lastPlayedSec, t.excludedFromTaste
        )

        fun buildProfile(p: TasteLane.CircadianProfileKt): Any {
            fun vec(v: TasteLane.DaypartVectorKt) =
                construct(daypartClass, v.bpmTarget, v.energyTarget, v.brightnessTarget, v.freshnessBias)
            return construct(profileClass, vec(p.morning), vec(p.afternoon), vec(p.evening), vec(p.lateNight))
        }

        fun buildBlendCandidate(c: TasteLane.BlendTrackKt): Any = construct(
            blendCandidateClass,
            c.id, c.title, c.artist, c.album, c.durationSec, c.bpm, c.key,
            c.genres, c.addedAtMs, c.coOccurrenceCount, c.excludedFromTaste
        )

        fun buildBlendSeed(s: TasteLane.BlendSeedKt): Any = construct(
            blendSeedClass,
            s.memberId, s.artists, s.genres,
            s.recentTracks.map { (title, artist) -> construct(blendRecentClass, title, artist) }
        )

        fun readNeuroRow(row: Any): TasteLane.TasteRow {
            val candidate = field(row, "candidate")
            val breakdown = field(row, "breakdown")
            return TasteLane.TasteRow(
                track = TasteLane.TasteTrack(
                    id = field(candidate, "id") as String,
                    title = field(candidate, "title") as String,
                    artist = field(candidate, "artist") as String,
                    album = (field(candidate, "album") as? String).orEmpty(),
                    source = (field(candidate, "source") as Number).toInt(),
                    bpm = (field(candidate, "bpm") as Number).toFloat(),
                    key = field(candidate, "key") as String,
                    energy = (field(candidate, "energy") as Number).toFloat(),
                    valence = (field(candidate, "valence") as Number).toFloat(),
                    acousticSim = (field(candidate, "acousticSim") as Number).toFloat(),
                    userAffinity = (field(candidate, "userAffinity") as Number).toFloat(),
                    lastPlayedSec = (field(candidate, "lastPlayedSec") as Number).toLong(),
                    excludedFromTaste = field(candidate, "excludedFromTaste") as Boolean
                ),
                score = (field(row, "score") as Number).toFloat(),
                breakdown = TasteLane.TasteBreakdown(
                    base = (field(breakdown, "base") as Number).toFloat(),
                    satiationPenalty = (field(breakdown, "satiationPenalty") as Number).toFloat(),
                    harmonicBonus = (field(breakdown, "harmonicBonus") as Number).toFloat(),
                    circadianBonus = (field(breakdown, "circadianBonus") as Number).toFloat(),
                    tempoDriftPenalty = (field(breakdown, "tempoDriftPenalty") as Number).toFloat(),
                    bpmDistance = (field(breakdown, "bpmDistance") as Number).toFloat(),
                    camelotDistance = (field(breakdown, "camelotDistance") as Number).toInt(),
                    freshness = (field(breakdown, "freshness") as Number).toFloat()
                ),
                reason = (field(row, "reason") as? String).orEmpty()
            )
        }

        /** Resolve one method by name prefix + parameter count (Kotlin
         *  value-class mangling tolerance — see the class docs). */
        private fun findMethod(cls: Class<*>, name: String, params: Int): java.lang.reflect.Method =
            cls.methods.firstOrNull {
                it.name == name && it.parameterCount == params
            } ?: cls.methods.firstOrNull {
                it.name.startsWith("$name-") && it.parameterCount == params
            } ?: throw NoSuchMethodException("${cls.name}.$name/$params")

        /** Construct a generated record, adapting numeric args to the
         *  erased JVM parameter types (UByte→byte, UInt→int, ULong→long). */
        private fun construct(clazz: Class<*>, vararg args: Any?): Any {
            val ctor = clazz.constructors.first { it.parameterCount == args.size }
            val adapted = ctor.parameterTypes.mapIndexed { i, type -> adapt(args[i], type) }
            return ctor.newInstance(*adapted.toTypedArray())
        }

        private fun adapt(value: Any?, type: Class<*>): Any? = when {
            value is Number && type == Byte::class.javaPrimitiveType -> value.toByte()
            value is Number && type == Int::class.javaPrimitiveType -> value.toInt()
            value is Number && type == Long::class.javaPrimitiveType -> value.toLong()
            value is Number && type == Float::class.javaPrimitiveType -> value.toFloat()
            value is Number && type == Double::class.javaPrimitiveType -> value.toDouble()
            value is Number && type == Short::class.javaPrimitiveType -> value.toShort()
            else -> value
        }

        companion object {
            /** Read a property off a generated record: getter first, then
             *  the backing field (data-class fields are public finals). */
            fun field(target: Any, property: String): Any? {
                val getter = "get" + property.replaceFirstChar { it.uppercase() }
                target.javaClass.methods.firstOrNull {
                    it.name == getter && it.parameterCount == 0
                }?.let { return it.invoke(target) }
                val f = target.javaClass.getDeclaredField(property)
                f.isAccessible = true
                return f.get(target)
            }
        }
    }
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
