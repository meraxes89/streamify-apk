package com.streamify.app.navigation

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.streamify.app.data.spotify.SpotifyAuthManager
import com.streamify.app.jam.JamEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SpotifyDeepLinkHandler — single-responsibility delegate for the
 * `streamify://` deep-link surface (sprint P2, god-object decomposition).
 *
 * Owns exactly two intents:
 *   1. `streamify://jam/<CODE>`  — a 6-char Jam invite code; parks it on the
 *      engine and emits it onto the navigation event flow.
 *   2. `streamify://callback|spotify-auth?code=…` — the Spotify OAuth
 *      redirect; exchanges the code, imports the taste profile and toasts
 *      the outcome.
 *
 * Extracted verbatim from MainActivity (it previously lived inline in
 * onNewIntent/handleSpotifyCallback): behavior-identical, no new
 * dependencies, purely a boundary move so the Activity composition surface
 * stops accumulating unrelated concerns.
 */
object SpotifyDeepLinkHandler {

    fun handle(activity: ComponentActivity, intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "streamify" && uri.host == "jam") {
            val code = uri.lastPathSegment?.uppercase()?.takeIf { it.length == 6 }
            if (code != null) {
                JamEngine.pendingInviteCode = code
                JamEngine.inviteNavigationEvents.tryEmit(code)
            }
            return
        }
        if (uri.scheme == "streamify" && (uri.host == "callback" || uri.host == "spotify-auth")) {
            val authCode = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            if (!authCode.isNullOrEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    val spotifyAuth = SpotifyAuthManager(activity)
                    val dbPath = activity.getDatabasePath("streamify_universal.db").absolutePath
                    spotifyAuth.handleAuthCallback(authCode, dbPath) { count ->
                        if (count >= 0) {
                            Toast.makeText(activity, "Spotify connected! Synced $count tracks into your taste profile 🎵", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Spotify connected successfully! 🎵", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else if (!error.isNullOrEmpty()) {
                Toast.makeText(activity, "Spotify auth note: $error", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
