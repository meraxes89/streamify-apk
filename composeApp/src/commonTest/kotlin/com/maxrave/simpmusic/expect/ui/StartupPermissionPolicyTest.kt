package com.maxrave.simpmusic.expect.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupPermissionPolicyTest {
    @Test
    fun blocksPromptWhileEntranceOrSplashIsVisible() {
        assertFalse(
            shouldPromptStartupPermissions(
                isEntranceBlocking = true,
                hasAllPermissions = false,
                doNotAskAgain = false,
                alreadyCheckedThisSession = false,
            ),
        )
    }

    @Test
    fun skipsPromptWhenPermissionsAlreadyGrantedOrSuppressed() {
        assertFalse(
            shouldPromptStartupPermissions(
                isEntranceBlocking = false,
                hasAllPermissions = true,
                doNotAskAgain = false,
                alreadyCheckedThisSession = false,
            ),
        )
        assertFalse(
            shouldPromptStartupPermissions(
                isEntranceBlocking = false,
                hasAllPermissions = false,
                doNotAskAgain = true,
                alreadyCheckedThisSession = false,
            ),
        )
        assertFalse(
            shouldPromptStartupPermissions(
                isEntranceBlocking = false,
                hasAllPermissions = false,
                doNotAskAgain = false,
                alreadyCheckedThisSession = true,
            ),
        )
    }

    @Test
    fun promptsOnceAfterEntranceWhenPermissionsMissingAndNotSuppressed() {
        assertTrue(
            shouldPromptStartupPermissions(
                isEntranceBlocking = false,
                hasAllPermissions = false,
                doNotAskAgain = false,
                alreadyCheckedThisSession = false,
            ),
        )
    }
}
