package com.streamify.announcements

actual class Ed25519ManifestSignatureVerifier actual constructor() : ManifestSignatureVerifier {
    /**
     * Fail closed on Wasm until WebCrypto Ed25519 verification is wired, so forged
     * remote manifests cannot bypass signature checks (AnnouncementEngine falls back
     * to LocalAnnouncementDefaults).
     */
    override fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean = false
}
