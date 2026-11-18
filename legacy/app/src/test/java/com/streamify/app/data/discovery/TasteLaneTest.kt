package com.streamify.app.data.discovery

import com.streamify.app.data.models.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TasteLaneTest — WS-D JVM contract suite (hermetic: a mock backend, no
 * host `.so`, no network).
 *
 * Pins the lane's Kotlin-side contracts:
 *  * D4 — reason strings survive the round trip onto [Track.reason]
 *    (the queue-row chip);
 *  * D7 — the taste-exclusion guard is consulted at the mapping
 *    boundary (`excludedFromTaste` rides into the engine lane);
 *  * D5 — the blend badge math ("N others like this" =
 *    overlapMembers − 1, the local viewer is the implicit first match);
 *  * the degraded backend yields empty results instead of throwing.
 */
class TasteLaneTest {

    private class RecordingBackend : TasteLane.TasteBackend {
        override val availability = TasteLane.Availability.MOCK
        var lastRadioCandidates: List<TasteLane.TasteTrack>? = null
        var lastDaylistCandidates: List<TasteLane.TasteTrack>? = null

        override fun generateRadioQueue(
            seed: TasteLane.TasteTrack,
            candidates: List<TasteLane.TasteTrack>,
            state: TasteLane.BrainStateKt,
            nowSec: Long,
            hourOfDay: Int,
            targetCount: Int,
            profile: TasteLane.CircadianProfileKt
        ): List<TasteLane.TasteRow> {
            lastRadioCandidates = candidates
            return candidates.take(targetCount).map { c ->
                TasteLane.TasteRow(
                    track = c,
                    score = 0.9f,
                    breakdown = TasteLane.TasteBreakdown(
                        base = 0.5f, satiationPenalty = 0f, harmonicBonus = 0.1f,
                        circadianBonus = 0.06f, tempoDriftPenalty = 0f,
                        bpmDistance = 2f, camelotDistance = 0, freshness = 1f
                    ),
                    reason = "locked to your ${seed.bpm.toInt()} BPM flow"
                )
            }
        }

        override fun generateDaylist(
            candidates: List<TasteLane.TasteTrack>,
            nowSec: Long,
            hourOfDay: Int,
            targetCount: Int,
            profile: TasteLane.CircadianProfileKt
        ): List<TasteLane.TasteRow> {
            lastDaylistCandidates = candidates
            return candidates.take(targetCount).map { c ->
                TasteLane.TasteRow(track = c, score = 0.8f, breakdown = null, reason = "fits your slot")
            }
        }

        override fun scoreGroupBlend(
            candidates: List<TasteLane.BlendTrackKt>,
            seeds: List<TasteLane.BlendSeedKt>,
            nowMs: Long
        ): List<TasteLane.BlendRowKt> =
            candidates.map { c ->
                TasteLane.BlendRowKt(
                    id = c.id,
                    title = c.title,
                    artist = c.artist,
                    score = 0.7f,
                    overlapMembers = seeds.size,
                    mutualBlend = seeds.size >= 2,
                    memberMatches = seeds.map { it.memberId },
                    reason = "${seeds.size - 1} others like this"
                )
            }
    }

    private lateinit var backend: RecordingBackend

    @Before
    fun setUp() {
        backend = RecordingBackend()
        TasteLane.installBackend(backend)
    }

    @After
    fun tearDown() {
        TasteLane.reset()
    }

    @Test
    fun `reason strings ride onto the playable Track model (D4)`() {
        val seed = TasteLane.TasteTrack(id = "seed", title = "Seed", artist = "A", bpm = 124f)
        val rows = TasteLane.generateRadioQueue(
            seed = seed,
            candidates = listOf(seed.copy(id = "c1", title = "C1", artist = "B")),
            state = TasteLane.BrainStateKt.FLOW,
            nowSec = 1_000_000L,
            hourOfDay = 9,
            targetCount = 5
        )
        assertEquals(1, rows.size)
        val track = rows.first().toTrack()
        assertEquals("C1", track.title)
        assertNotNull(track.reason)
        assertEquals("locked to your 124 BPM flow", track.reason)
    }

    @Test
    fun `the exclusion guard flags candidates at the boundary (D7)`() {
        val excluded = Track(id = 1, title = "Excluded Song", artist = "Secret Artist")
        val normal = Track(id = 2, title = "Normal Song", artist = "Open Artist")
        val guard = TasteExclusionIndex(excludedTrackKeys = setOf(TasteExclusionIndex.trackKey("Excluded Song", "Secret Artist")))
        val candidates = listOf(excluded, normal).map { it.toTasteTrack(source = 0, guard = guard) }

        TasteLane.generateRadioQueue(
            seed = normal.toTasteTrack(source = 1, guard = guard),
            candidates = candidates,
            state = TasteLane.BrainStateKt.FLOW,
            nowSec = 0L,
            hourOfDay = 9,
            targetCount = 5
        )

        val recorded = backend.lastRadioCandidates.orEmpty()
        assertEquals(2, recorded.size)
        assertTrue(recorded.first { it.title == "Excluded Song" }.excludedFromTaste)
        assertTrue(!recorded.first { it.title == "Normal Song" }.excludedFromTaste)
    }

    @Test
    fun `blend badge math counts others not the viewer (D5)`() {
        val rows = TasteLane.scoreGroupBlend(
            candidates = listOf(TasteLane.BlendTrackKt(id = 1, title = "T", artist = "A")),
            seeds = listOf(
                TasteLane.BlendSeedKt("m1", listOf("x")),
                TasteLane.BlendSeedKt("m2", listOf("x")),
                TasteLane.BlendSeedKt("m3", listOf("x")),
                TasteLane.BlendSeedKt("m4", listOf("x"))
            ),
            nowMs = 0L
        )
        assertEquals(1, rows.size)
        // 4 members overlap; the viewer is one of them → 3 others.
        assertEquals(4, rows.first().overlapMembers)
        assertEquals(3, rows.first().othersCount)
        assertEquals("3 others like this", rows.first().reason)
    }

    @Test
    fun `default bpm and camelot fallbacks apply on metadata-less tracks`() {
        val bare = Track(id = 5, title = "Bare", artist = "Nobody")
        val mapped = bare.toTasteTrack(source = 2)
        assertEquals(120f, mapped.bpm)
        assertEquals("8A", mapped.key)
        assertEquals(2, mapped.source)
    }

    @Test
    fun `degraded backend yields empty results not throws`() {
        TasteLane.reset()
        // Force the degraded path: a backend that throws on every op.
        TasteLane.installBackend(object : TasteLane.TasteBackend {
            override val availability = TasteLane.Availability.NATIVE_UNAVAILABLE
            override fun generateRadioQueue(
                seed: TasteLane.TasteTrack,
                candidates: List<TasteLane.TasteTrack>,
                state: TasteLane.BrainStateKt,
                nowSec: Long,
                hourOfDay: Int,
                targetCount: Int,
                profile: TasteLane.CircadianProfileKt
            ): List<TasteLane.TasteRow> = throw IllegalStateException("native lane down")

            override fun generateDaylist(
                candidates: List<TasteLane.TasteTrack>,
                nowSec: Long,
                hourOfDay: Int,
                targetCount: Int,
                profile: TasteLane.CircadianProfileKt
            ): List<TasteLane.TasteRow> = throw IllegalStateException("native lane down")

            override fun scoreGroupBlend(
                candidates: List<TasteLane.BlendTrackKt>,
                seeds: List<TasteLane.BlendSeedKt>,
                nowMs: Long
            ): List<TasteLane.BlendRowKt> = throw IllegalStateException("native lane down")
        })
        assertTrue(TasteLane.generateRadioQueue(TasteLane.TasteTrack("s", "S", "A"), emptyList(), TasteLane.BrainStateKt.FLOW, 0, 9, 5).isEmpty())
        assertTrue(TasteLane.generateDaylist(emptyList(), 0, 9, 5).isEmpty())
        assertTrue(TasteLane.scoreGroupBlend(emptyList(), emptyList(), 0).isEmpty())
    }
}
