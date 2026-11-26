package com.maxrave.simpmusic.data.update

import com.streamify.announcements.ManifestSignatureVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdatePolicyEngineTest {

    private class FakeVerifier(private val shouldVerify: Boolean) : ManifestSignatureVerifier {
        var lastCanonicalPayload: String? = null
        override fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean {
            lastCanonicalPayload = payload
            return shouldVerify
        }
    }

    @Test
    fun evaluateUpdatePolicyCoversAllUrgencyTiersAndVerificationGate() {
        val now = 1_000_000_000L
        val hourMs = 3_600_000L

        val softManifest = UpdateManifest(version = "1.0.4", versionCode = 104, graceHours = 48)
        val softState = evaluateUpdatePolicy(softManifest, installedCode = 100, firstSeenAtMs = now - 2 * hourMs, nowMs = now, isPolicyVerified = true)
        assertEquals(UpdateUrgency.SOFT, softState.urgency)
        assertFalse(softState.isOnlineStreamingBlocked)

        val activeState = evaluateUpdatePolicy(softManifest, installedCode = 100, firstSeenAtMs = now - 20 * hourMs, nowMs = now, isPolicyVerified = true)
        assertEquals(UpdateUrgency.ACTIVE_5S, activeState.urgency)
        assertFalse(activeState.isOnlineStreamingBlocked)

        val urgentState = evaluateUpdatePolicy(softManifest, installedCode = 100, firstSeenAtMs = now - 40 * hourMs, nowMs = now, isPolicyVerified = true)
        assertEquals(UpdateUrgency.URGENT_10S, urgentState.urgency)
        assertFalse(urgentState.isOnlineStreamingBlocked)

        val expiredVerified = evaluateUpdatePolicy(softManifest, installedCode = 100, firstSeenAtMs = now - 49 * hourMs, nowMs = now, isPolicyVerified = true)
        assertEquals(UpdateUrgency.ENFORCED, expiredVerified.urgency)
        assertEquals(0, expiredVerified.remainingHours)
        assertTrue(expiredVerified.isOnlineStreamingBlocked)

        // Unsigned/unverified policy must never trigger the online streaming capability gate
        val expiredUnverified = evaluateUpdatePolicy(softManifest, installedCode = 100, firstSeenAtMs = now - 49 * hourMs, nowMs = now, isPolicyVerified = false)
        assertEquals(UpdateUrgency.ENFORCED, expiredUnverified.urgency)
        assertFalse(expiredUnverified.isOnlineStreamingBlocked)
    }

    @Test
    fun breakingMinSupportedOrRecalledShaTriggersEnforcedWhenVerified() {
        val manifest = UpdateManifest(
            version = "1.0.5",
            versionCode = 105,
            minSupportedVersionCode = 102,
            recalledShas = listOf("badsha123"),
            graceHours = 48,
        )
        val byMinCode = evaluateUpdatePolicy(manifest, installedCode = 101, firstSeenAtMs = 1000L, nowMs = 1000L, isPolicyVerified = true)
        assertTrue(byMinCode.isBreaking)
        assertEquals(UpdateUrgency.ENFORCED, byMinCode.urgency)
        assertTrue(byMinCode.isOnlineStreamingBlocked)

        val byRecall = evaluateUpdatePolicy(manifest, installedCode = 105, installedSha = "badsha123", firstSeenAtMs = 1000L, nowMs = 1000L, isPolicyVerified = true)
        assertTrue(byRecall.isBreaking)
        assertTrue(byRecall.isOnlineStreamingBlocked)
    }

    @Test
    fun verifyAndCompareValidatesSignatureAndCanonicalizesSignatureHex() {
        val rawSigned = """{"version":"1.0.5","versionCode":200,"signatureHex":"${"a".repeat(128)}"}"""
        val manifest = UpdateManifest(version = "1.0.5", versionCode = 200, signatureHex = "a".repeat(128))

        val passVerifier = FakeVerifier(shouldVerify = true)
        val passResolver = UpdateResolver(signatureVerifier = passVerifier, pinnedPublicKeyHex = "b".repeat(64))
        val ok = passResolver.verifyAndCompare(rawSigned, manifest, installedCode = 100, installedSha = "")
        val available = assertIs<UpdateResult.Available>(ok)
        assertTrue(available.isPolicyVerified)
        assertTrue(passVerifier.lastCanonicalPayload?.contains(""""signatureHex": """"") == true)

        val failVerifier = FakeVerifier(shouldVerify = false)
        val failResolver = UpdateResolver(signatureVerifier = failVerifier, pinnedPublicKeyHex = "b".repeat(64))
        val err = failResolver.verifyAndCompare(rawSigned, manifest, installedCode = 100, installedSha = "")
        assertIs<UpdateResult.Error>(err)
    }

    @Test
    fun updateStateHolderStateFlowUpdatesAndClears() {
        UpdateStateHolder.clear()
        assertFalse(UpdateStateHolder.isOnlineStreamingBlocked)

        UpdateStateHolder.update(
            UpdatePolicyState(
                latestManifest = UpdateManifest(version = "2.0.0", versionCode = 200, isBreaking = true),
                isPolicyVerified = true,
                isBreaking = true,
                urgency = UpdateUrgency.ENFORCED,
                remainingHours = 0,
            ),
        )
        assertTrue(UpdateStateHolder.state.value.isOnlineStreamingBlocked)
        assertEquals("2.0.0", UpdateStateHolder.latestManifest?.version)

        UpdateStateHolder.clear()
        assertFalse(UpdateStateHolder.state.value.isOnlineStreamingBlocked)
    }

    @Test
    fun nightlySameVersionCodeDifferentShaTriggersUpdate() {
        val resolver = UpdateResolver()
        val nightlyManifest = UpdateManifest(
            version = "nightly",
            versionCode = 104,
            channel = "nightly",
            gitSha = "newsha789",
        )
        val sameSha = resolver.compareAndReturn(nightlyManifest, installedCode = 104, installedSha = "newsha789")
        assertIs<UpdateResult.UpToDate>(sameSha)

        val newerSha = resolver.compareAndReturn(nightlyManifest, installedCode = 104, installedSha = "oldsha123")
        val available = assertIs<UpdateResult.Available>(newerSha)
        assertEquals("nightly", available.manifest.channel)
    }

    @Test
    fun updatePreferencesKeysAndDefaultsAreStable() {
        assertEquals("STREAMIFY_NIGHTLY_UPDATES", UpdatePreferences.KEY_NIGHTLY_ENABLED)
        assertEquals("STREAMIFY_AUTO_INSTALL_UPDATES", UpdatePreferences.KEY_AUTO_INSTALL_ENABLED)
    }
}
