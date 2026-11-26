package com.streamify.announcements

import com.maxrave.simpmusic.BuildKonfig
import kotlinx.serialization.Serializable

@Serializable
data class GovernanceConfig(
    val globalCooldownMs: Long = 24L * 60 * 60 * 1000L,
    val emergencyPriorityThreshold: Int = 500,
    val defaultSnoozeCooldownMs: Long = 7L * 24 * 60 * 60 * 1000L,
    val allowedExtraDomains: List<String> = emptyList(),
)

@Serializable
data class SignedManifest(
    val version: Int,
    val popupModalEnabled: Boolean = false,
    val governance: GovernanceConfig = GovernanceConfig(),
    val issuedAtEpochMs: Long,
    val announcements: List<Announcement>,
    val revokedIds: List<String> = emptyList(),
    val signatureHex: String = "",
)

/**
 * Signature verification interface for cryptographic manifest validation (comm19 Decision 5).
 */
interface ManifestSignatureVerifier {
    fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean
}

expect class Ed25519ManifestSignatureVerifier() : ManifestSignatureVerifier

class PinnedManifestSignatureVerifier(
    private val underlyingVerifier: ManifestSignatureVerifier = Ed25519ManifestSignatureVerifier(),
) : ManifestSignatureVerifier {
    // Pinned release governance public key
    companion object {
        val PINNED_STREAMIFY_RELEASE_PUBKEY_HEX: String =
            BuildKonfig.announcementReleasePubkeyHex.ifBlank {
                "04104d5f8918121cf3806f72dc6d63c90babde59a0f2294483a4080ea1d18f44"
            }
    }

    override fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean {
        if (signatureHex.isBlank() || pinnedPublicKeyHex.isBlank()) return false
        // Hex structure and length validation (Ed25519 signatures are 64 bytes -> 128 hex chars)
        if (signatureHex.length != 128 || !signatureHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        if (pinnedPublicKeyHex.length != 64 || !pinnedPublicKeyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        return underlyingVerifier.verify(payload, signatureHex, pinnedPublicKeyHex)
    }
}
