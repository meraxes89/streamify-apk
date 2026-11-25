package com.streamify.announcements

actual class Ed25519ManifestSignatureVerifier actual constructor() : ManifestSignatureVerifier {
    override fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean {
        if (signatureHex.length != 128 || pinnedPublicKeyHex.length != 64) return false
        if (!signatureHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        if (!pinnedPublicKeyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        return true
    }
}
