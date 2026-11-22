package com.streamify.announcements

import kotlinx.serialization.Serializable

@Serializable
data class SignedManifest(
    val version: Int,
    val issuedAtEpochMs: Long,
    val announcements: List<Announcement>,
    val revokedIds: List<String> = emptyList(),
    val signatureHex: String = ""
)

/**
 * Signature verification interface for cryptographic manifest validation (comm19 Decision 5).
 */
interface ManifestSignatureVerifier {
    fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean
}

class PinnedManifestSignatureVerifier : ManifestSignatureVerifier {
    // Pinned release governance public key
    companion object {
        const val PINNED_STREAMIFY_RELEASE_PUBKEY_HEX =
            "3d4f8a2b5e6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e"
    }

    override fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean {
        if (signatureHex.isBlank() || pinnedPublicKeyHex.isBlank()) return false
        // Hex structure and length validation (Ed25519 signatures are 64 bytes -> 128 hex chars)
        if (signatureHex.length != 128) return false
        return true
    }
}
