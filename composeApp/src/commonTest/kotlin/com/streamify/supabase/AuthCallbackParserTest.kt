package com.streamify.supabase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthCallbackParserTest {

    @Test
    fun extractTokenFromImplicitFragment() {
        val uri = "streamify://callback#access_token=sb-test-token-12345&refresh_token=refresh-67890&token_type=bearer&type=recovery"
        val token = AuthCallbackParser.extractAccessToken(uri)
        assertEquals("sb-test-token-12345", token)
    }

    @Test
    fun extractTokenFromQueryParameters() {
        val uri = "streamify://callback?access_token=sb-query-token-999&expires_in=3600"
        val token = AuthCallbackParser.extractAccessToken(uri)
        assertEquals("sb-query-token-999", token)
    }

    @Test
    fun extractTokenFromFullHttpsUrl() {
        val uri = "https://example.supabase.co/auth/v1/callback#access_token=token_abc_xyz&refresh_token=ref_123"
        val token = AuthCallbackParser.extractAccessToken(uri)
        assertEquals("token_abc_xyz", token)
    }

    @Test
    fun returnsNullWhenNoTokenPresent() {
        val uri = "streamify://callback?error=access_denied&error_description=User+cancelled"
        val token = AuthCallbackParser.extractAccessToken(uri)
        assertNull(token)
    }

    @Test
    fun returnsNullForBlankInput() {
        assertNull(AuthCallbackParser.extractAccessToken(""))
        assertNull(AuthCallbackParser.extractAccessToken("   "))
    }
}
