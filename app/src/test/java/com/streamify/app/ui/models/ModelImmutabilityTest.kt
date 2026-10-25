package com.streamify.app.ui.models

import com.streamify.app.data.models.Track
import com.streamify.app.data.models.TrackNative
import com.streamify.app.jam.JamEngine
import com.streamify.app.jam.JamGovernance
import com.streamify.app.viewmodel.JamUiState
import com.streamify.app.viewmodel.PlayerState
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MODEL IMMUTABILITY REGRESSION TESTS (pure JVM, reflection)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Phase 5 pins the @Immutable/@Stable contract STRUCTURALLY: the Compose
 * compiler's smart-skipping is only sound while these models stay
 * all-val, all-immutable-type, replace-never-mutate. A future commit that
 * adds a `var` field, a mutable collection, or a mutable holder type
 * (JSONObject, array, MutableState) to any annotated model silently
 * breaks skipping for every composable that takes it — this suite fails
 * the build the moment that happens.
 *
 * (Annotation presence itself is NOT reflected: the Compose stability
 * annotations use BINARY retention. The structural check below is the
 * stronger guarantee anyway.)
 */
class ModelImmutabilityTest {

    /** Field types allowed inside an @Immutable model (contract: treated as
     *  immutable values — lists/maps are only ever replaced wholesale). */
    private val allowedTypes = setOf(
        java.lang.String::class.java,
        java.lang.Integer::class.java, java.lang.Long::class.java,
        java.lang.Float::class.java, java.lang.Double::class.java,
        java.lang.Boolean::class.java, java.lang.Byte::class.java,
        java.lang.Short::class.java, java.lang.Character::class.java,
        java.util.List::class.java, java.util.Set::class.java,
        java.util.Map::class.java,
        Track::class.java, VirtualShelfTrack::class.java,
        JamEngine.JamSession::class.java,
        // Pure Compose value class (ULong) — @Immutable by contract.
        Color::class.java
    )

    private fun isAllowed(type: Class<*>): Boolean =
        type in allowedTypes || type.isEnum || type.isPrimitive

    private fun assertImmutableContract(clazz: Class<*>) {
        val fields = clazz.declaredFields
        assertTrue("${clazz.simpleName} has no fields to check", fields.isNotEmpty())
        for (f in fields) {
            // Kotlin val -> final backing field; a var would be non-final.
            assertTrue(
                "${clazz.simpleName}.${f.name} must be a val (final field) — " +
                        "mutating an @Immutable model breaks Compose smart skipping",
                Modifier.isFinal(f.modifiers)
            )
            assertTrue(
                "${clazz.simpleName}.${f.name} has type ${f.type.simpleName} which is not " +
                        "on the immutable allowlist (add it only after auditing equality/" +
                        "mutation semantics)",
                isAllowed(f.type)
            )
        }
        // Synthetic fields from Kotlin internals (e.g. Companion refs) are fine:
        // they are final and hold immutable singletons, so the loop above already
        // validated them.
    }

    // ── Track.kt ────────────────────────────────────────────────────────────

    @Test
    fun `Track is structurally immutable`() = assertImmutableContract(Track::class.java)

    @Test
    fun `TrackNative is structurally immutable`() = assertImmutableContract(TrackNative::class.java)

    // ── PlayerModels.kt ─────────────────────────────────────────────────────

    @Test
    fun `PlayerState is structurally immutable`() =
        assertImmutableContract(PlayerState::class.java)

    // ── UiModels.kt ─────────────────────────────────────────────────────────

    @Test
    fun `VirtualShelfTrack is structurally immutable`() =
        assertImmutableContract(VirtualShelfTrack::class.java)

    @Test
    fun `VirtualShelf is structurally immutable`() =
        assertImmutableContract(VirtualShelf::class.java)

    @Test
    fun `AmbientPalette is structurally immutable`() =
        assertImmutableContract(AmbientPalette::class.java)

    // ── Jam models (JamEngine.kt / JamGovernance.kt / JamViewModel.kt) ─────

    @Test
    fun `JamEngine Member is structurally immutable`() =
        assertImmutableContract(JamEngine.Member::class.java)

    @Test
    fun `JamEngine JamSession is structurally immutable`() =
        assertImmutableContract(JamEngine.JamSession::class.java)

    @Test
    fun `JamEngine MeshPeer is structurally immutable`() =
        assertImmutableContract(JamEngine.MeshPeer::class.java)

    @Test
    fun `JamEngine SyncTelemetry is structurally immutable`() =
        assertImmutableContract(JamEngine.SyncTelemetry::class.java)

    @Test
    fun `JamGovernance MemberAcl is structurally immutable`() =
        assertImmutableContract(JamGovernance.MemberAcl::class.java)

    @Test
    fun `JamGovernance MemberReport is structurally immutable`() =
        assertImmutableContract(JamGovernance.MemberReport::class.java)

    @Test
    fun `JamUiState Active is structurally immutable`() =
        assertImmutableContract(JamUiState.Active::class.java)

    @Test
    fun `JamUiState Error is structurally immutable`() =
        assertImmutableContract(JamUiState.Error::class.java)

    // ── Deliberate exclusion, pinned in the contract docs ──────────────────

    @Test
    fun `HandshakeSnapshot is intentionally NOT in the immutable set`() {
        // It carries a mutable org.json.JSONObject — the reason it is not
        // annotated @Immutable. This test pins that decision: if someone
        // "fixes" the annotation without removing the JSONObject, the
        // structural suite above (not this case) will catch it if added.
        val hasJsonObjectField = JamEngine.HandshakeSnapshot::class.java.declaredFields
            .any { it.type.name == "org.json.JSONObject" }
        assertTrue(hasJsonObjectField)
    }
}
