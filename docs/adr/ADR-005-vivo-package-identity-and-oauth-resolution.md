# ADR-005: Vivo package identity, Web OAuth callback, and resilient update resolution

* Status: Accepted
* Date: 2026-11-23
* Verified against: 996f7b0c (staleness protocol in docs/adr/README.md)

## Context

Vivo's OriginOS Island and lockscreen media widgets only track audio players whose Android application package name is present on Vivo's proprietary system allowlist. A standard third-party application cannot register itself without shell or root access. Streamify provides a dedicated `vivoAndroidApp` module that compiles the application unchanged under `applicationId = "com.spotify.music"`.

This spoofed package name introduces two critical runtime failures:
1. **Google Identity Rejection**: When [GoogleOnboardingAuthScreen.kt](file:///data/data/com.termux/files/home/streamify-apk/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/screen/login/GoogleOnboardingAuthScreen.kt) invokes `CredentialManager.getCredential(...)`, Google Play Services verifies caller identity against Google Cloud Console. Because `com.spotify.music` signed with Streamify's key does not match Spotify AB's registered certificate, Credential Manager throws `GetCredentialException`.
2. **Broken Browser OAuth Return Loop**: The app's fallback (`tryLaunchWebOAuth`) delegates authentication to the system browser (`ACTION_VIEW`). Supabase redirects the browser to `streamify://callback#access_token=...`. While `AndroidManifest.xml` declared an intent-filter for scheme `streamify` and host `callback`, neither `MainActivity.kt` nor `App.kt` processed the callback intent, leaving the user unauthenticated and trapped in a redirect loop.

Furthermore, in-app update checks for Vivo builds were obstructed:
- `UpdateResolver.kt` explicitly skipped assets containing `"vivo"` during GitHub Releases API fallback.
- `PlatformBinaryResolver.android.kt` only checked `"android-vivo-universal"` and `"android-vivo-arm64"`, omitting `"android-vivo"` and `"android-vivo-arm64-v8a"`.
- GitHub's `/releases/latest` endpoint strictly excludes pre-releases, returning 404 for CDN update manifests on beta tags.

## Decision

1. **Deep Link Callback Interception**:
   - In [App.kt](file:///data/data/com.termux/files/home/streamify-apk/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/App.kt), `LaunchedEffect(intent)` intercepts `data.scheme == "streamify" && data.host == "callback"`.
   - The raw URI is handed to `StreamifyAuthManager.handleAuthCallback(uriString, platformContext)`.

2. **Universal Callback Token Parser**:
   - Authored [AuthCallbackParser.kt](file:///data/data/com.termux/files/home/streamify-apk/streamify-modules/supabase/src/commonMain/kotlin/com/streamify/supabase/AuthCallbackParser.kt) in `streamify-modules/supabase`.
   - Safely parses access tokens from OAuth implicit fragment redirects (`#access_token=...`) and query strings (`?access_token=...`).

3. **Supabase Direct Token Authentication**:
   - Implemented `signInWithAccessToken(token)` in [SupabaseClient.kt](file:///data/data/com.termux/files/home/streamify-apk/streamify-modules/supabase/src/commonMain/kotlin/com/streamify/supabase/SupabaseClient.kt).
   - Validates the token against Supabase `/auth/v1/user`, constructs the authenticated `UserProfile`, resolves server admin claims via RPC, and initializes the local session.

4. **Multiplatform Contract Alignment**:
   - Declared `suspend fun handleAuthCallback(callbackUri: String, context: Any? = null): Result<UserProfile>` in [StreamifyAuthManager.kt](file:///data/data/com.termux/files/home/streamify-apk/streamify-modules/supabase/src/commonMain/kotlin/com/streamify/supabase/StreamifyAuthManager.kt).
   - Implemented native handling in `StreamifyAuthManager.android.kt`, and synchronized default user pass-throughs across JVM, iOS, and WasmJs targets.
   - Expanded exception catching in `StreamifyAuthManager.android.kt` to catch all `GetCredentialException` variants and cleanly trigger the Web OAuth fallback.

5. **Vivo Artifact Resolution & Pre-Release Resilience**:
   - In [UpdateResolver.kt](file:///data/data/com.termux/files/home/streamify-apk/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/data/update/UpdateResolver.kt), mapped Vivo APKs to `android-vivo-arm64`, `android-vivo-armeabi-v7a`, `android-vivo-universal`, and `android-vivo`.
   - In [PlatformBinaryResolver.android.kt](file:///data/data/com.termux/files/home/streamify-apk/composeApp/src/androidMain/kotlin/com/maxrave/simpmusic/data/update/PlatformBinaryResolver.android.kt), unified resolution across all Vivo keys, guaranteeing Vivo devices never download the universal package.
   - Pointed Tier 3 REST fallback to `/releases?per_page=1` supporting array and object JSON payloads, and added an authoritative pinned `version.json` probe against the target release tag.

## Alternatives Considered

- **Allowing Guest Access as an Onboarding Bypass**: Rejected by product requirement. Streamify enforces a strict gatekeeper on `GoogleOnboardingAuthScreen` requiring authenticated user identity.
- **Requesting Shizuku Privileges to Edit Vivo Settings**: Rejected. Requires Android Developer options and Shizuku pairing, which users rejected in testing.

## Consequences

**Positive**:
- Vivo users experience seamless authentication via browser OAuth return without getting trapped in redirect loops.
- In-app update checks correctly detect and download Vivo-specific binaries without overwriting the spoofed package identity.
- Pre-release and beta releases remain discoverable across all update tiers.

**Negative**:
- Vivo build authentication requires external browser delegation rather than the native in-app Credential Manager bottom sheet because Google Identity enforces package signature verification.

---
<!-- doc-verified-against: 996f7b0ce54e57cc23d646bf408be7cbe0aecada -->
