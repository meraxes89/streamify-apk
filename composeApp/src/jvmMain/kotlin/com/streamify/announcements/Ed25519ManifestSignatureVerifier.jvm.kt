package com.streamify.announcements

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

actual class Ed25519ManifestSignatureVerifier actual constructor() : ManifestSignatureVerifier {
    override fun verify(payload: String, signatureHex: String, pinnedPublicKeyHex: String): Boolean {
        if (signatureHex.length != 128 || pinnedPublicKeyHex.length != 64) return false
        return runCatching {
            val sigBytes = hexToByteArray(signatureHex)
            val rawKeyBytes = hexToByteArray(pinnedPublicKeyHex)
            val x509Header = byteArrayOf(
                0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(),
                0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(),
                0x70.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte(),
            )
            val spkiBytes = x509Header + rawKeyBytes
            val keySpec = X509EncodedKeySpec(spkiBytes)
            val keyFactory = runCatching {
                KeyFactory.getInstance("Ed25519")
            }.getOrElse {
                KeyFactory.getInstance("EdDSA")
            }
            val publicKey = keyFactory.generatePublic(keySpec)
            val sig = runCatching {
                Signature.getInstance("Ed25519")
            }.getOrElse {
                Signature.getInstance("EdDSA")
            }
            sig.initVerify(publicKey)
            sig.update(payload.toByteArray(Charsets.UTF_8))
            sig.verify(sigBytes)
        }.getOrDefault(false)
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val idx = i * 2
            result[i] = hex.substring(idx, idx + 2).toInt(16).toByte()
        }
        return result
    }
}
