package com.maxrave.simpmusic.expect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlDispatchPolicyTest {

    @Test
    fun testAllowedUrls() {
        assertTrue(UrlDispatchPolicy.isDispatchable("https://streamify.app"))
        assertTrue(UrlDispatchPolicy.isDispatchable("https://github.com/meraxes89/streamify-apk"))
        assertTrue(UrlDispatchPolicy.isDispatchable("http://localhost:8080/test"))
        assertTrue(UrlDispatchPolicy.isDispatchable("x-apple.systempreferences:com.apple.preference.security"))
    }

    @Test
    fun testBlockedDisallowedSchemes() {
        assertFalse(UrlDispatchPolicy.isDispatchable(""))
        assertFalse(UrlDispatchPolicy.isDispatchable("   "))
        assertFalse(UrlDispatchPolicy.isDispatchable("file:///etc/passwd"))
        assertFalse(UrlDispatchPolicy.isDispatchable("content://media/external/images/media"))
        assertFalse(UrlDispatchPolicy.isDispatchable("intent:#Intent;action=android.intent.action.VIEW;end"))
        assertFalse(UrlDispatchPolicy.isDispatchable("javascript:alert(1)"))
        assertFalse(UrlDispatchPolicy.isDispatchable("data:text/html,<html>"))
    }
}
