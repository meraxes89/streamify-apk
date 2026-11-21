package com.streamify.app.data.spotify
object SpotifyConfig {
    const val CLIENT_ID = "6f8a49c2d1ef4177894a4c4e976db57f" // Streamify Public PKCE Client
    const val REDIRECT_URI = "streamify://callback"
    const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative user-top-read"
    const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
    const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
}
