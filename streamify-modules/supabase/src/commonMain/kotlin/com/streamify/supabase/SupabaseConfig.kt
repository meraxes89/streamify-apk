package com.streamify.supabase

/**
 * Global Supabase Configuration & Credentials for Streamify Cloud Sync.
 */
object SupabaseConfig {
    const val SUPABASE_URL = "https://dbgtvzgwixerhnwvhtil.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRiZ3R2emd3aXhlcmhud3ZodGlsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY2OTAwMjgsImV4cCI6MjEwMjI2NjAyOH0.kcYyy_zKN1xlePkbXATgOzafdaCap09cphSuKCOHSas"
    const val GOOGLE_WEB_CLIENT_ID = "868888425624-9dfr5crtahiqph68tiba1acapvicnsug.apps.googleusercontent.com"

    /**
     * Default administrator profile for Sireen Yadav to enable out-of-the-box
     * cloud library synchronization and friend activity on Streamify.
     */
    val DEFAULT_USER = UserProfile(
        id = "8a47bc34-6317-448a-a3e3-ffbf3864b9ae",
        email = "sireenyadav@gmail.com",
        displayName = "Sireen Yadav",
        avatarUrl = "https://lh3.googleusercontent.com/a/ACg8ocJOuWED9eB7KM0k_WtdZtQfQxvoT7AOzu00BZhl7e2wFElUgb2f=s96-c",
        bio = "Music lover on Streamify",
        isAdmin = false
    )
}
