package com.streamify.supabase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthCallbackParserTest {

    @Test
    fun parsePkceCodeAndStateFromQuery() {
        val uri = "streamify://callback?code=AUTH_CODE_12345&state=STATE_NONCE_XYZ"
        val data = AuthCallbackParser.parse(uri)

        assertTrue(data.isSuccess)
        assertFalse(data.isError)
        assertEquals("AUTH_CODE_12345", data.code)
        assertEquals("STATE_NONCE_XYZ", data.state)
        assertNull(data.error)
    }

    @Test
    fun parsePkceCodeAndStateFromFragment() {
        val uri = "streamify://callback#code=FRAGMENT_CODE_999&state=FRAGMENT_STATE"
        val data = AuthCallbackParser.parse(uri)

        assertTrue(data.isSuccess)
        assertEquals("FRAGMENT_CODE_999", data.code)
        assertEquals("FRAGMENT_STATE", data.state)
    }

    @Test
    fun parseOAuthErrorResponse() {
        val uri = "streamify://callback?error=access_denied&error_code=403&error_description=User+declined+authorization&state=NONCE_123"
        val data = AuthCallbackParser.parse(uri)

        assertFalse(data.isSuccess)
        assertTrue(data.isError)
        assertEquals("access_denied", data.error)
        assertEquals("403", data.errorCode)
        assertEquals("User declined authorization", data.errorDescription)
        assertEquals("NONCE_123", data.state)
        assertNull(data.code)
    }

    @Test
    fun parseLegacyImplicitFlowFragment() {
        val uri = "streamify://callback#access_token=sb-test-token-12345&refresh_token=refresh-67890&token_type=bearer&expires_in=3600"
        val data = AuthCallbackParser.parse(uri)

        assertTrue(data.isSuccess)
        assertEquals("sb-test-token-12345", data.accessToken)
        assertEquals("refresh-67890", data.refreshToken)
        assertEquals("bearer", data.tokenType)
        assertEquals(3600L, data.expiresIn)

        // Backward compatibility method
        assertEquals("sb-test-token-12345", AuthCallbackParser.extractAccessToken(uri))
    }

    @Test
    fun returnsEmptyOnBlankOrInvalidInput() {
        val emptyData = AuthCallbackParser.parse("")
        assertFalse(emptyData.isSuccess)
        assertNull(emptyData.code)
        assertNull(emptyData.accessToken)

        val whitespaceData = AuthCallbackParser.parse("   ")
        assertFalse(whitespaceData.isSuccess)
        assertNull(AuthCallbackParser.extractAccessToken(""))
    }
}
