package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/**
 * Coordinates startup runtime permissions (notifications and local audio on Android 13+)
 * so they are requested only after entrance UI (splash / auth) has settled.
 */
interface StartupPermissionRequester {
    /** Returns true when all startup runtime permissions are already granted or not applicable. */
    fun hasAllRequiredPermissions(): Boolean

    /** Requests any missing startup runtime permissions from the OS. */
    fun requestMissingPermissions()
}

/**
 * Pure gate determining whether the startup permission rationale dialog should be shown.
 */
internal fun shouldPromptStartupPermissions(
    isEntranceBlocking: Boolean,
    hasAllPermissions: Boolean,
    doNotAskAgain: Boolean,
    alreadyCheckedThisSession: Boolean,
): Boolean = !isEntranceBlocking && !hasAllPermissions && !doNotAskAgain && !alreadyCheckedThisSession

@Composable
expect fun rememberStartupPermissionRequester(): StartupPermissionRequester
