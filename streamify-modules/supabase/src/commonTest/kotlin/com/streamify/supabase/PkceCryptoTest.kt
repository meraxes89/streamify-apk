package com.streamify.supabase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PkceCryptoTest {

    @Test
    fun verifyRfc7636AppendixBTestVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val derivedChallenge = PkceCrypto.deriveCodeChallenge(verifier)
        assertEquals(expectedChallenge, derivedChallenge)
    }

    @Test
    fun verifySha256KnownTestVectors() {
        // NIST SHA-256 test vector 1: Empty string
        val emptyHash = PkceCrypto.sha256("".encodeToByteArray())
        val emptyHex = emptyHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHex)

        // NIST SHA-256 test vector 2: "abc"
        val abcHash = PkceCrypto.sha256("abc".encodeToByteArray())
        val abcHex = abcHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abcHex)
    }

    @Test
    fun generateCodeVerifierMeetsRfcRequirements() {
        val verifier = PkceCrypto.generateCodeVerifier(64)
        assertEquals(64, verifier.length)

        val allowedCharset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        for (char in verifier) {
            assertTrue(allowedCharset.contains(char), "Character '$char' is not in RFC 7636 unreserved charset")
        }
    }

    @Test
    fun generateStateMeetsLengthAndCharset() {
        val state = PkceCrypto.generateState(32)
        assertEquals(32, state.length)

        val hexCharset = "0123456789abcdef"
        for (char in state) {
            assertTrue(hexCharset.contains(char), "Character '$char' is not valid hex")
        }
    }
}
