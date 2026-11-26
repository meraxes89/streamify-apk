---
id: S18
title: Identity & trust
tier: feature
status: active
owner: identity
sources: [core/service/loginSync/, streamify-modules/supabase/src/commonMain/kotlin/com/streamify/supabase/, server/src/slices/auth/]
tests: [streamify-modules/supabase/src/commonTest/]
---

# S18 — Identity & trust

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Supabase auth, PKCE, token refresh serialization, crypto vault and server authentication.

## Does not own

- Sync (S11), server security (S21).

## Current truth

- Client-side PKCE with a loopback server on desktop (`LoopbackAuthServer`); Vivo has a dedicated CustomTabs PKCE callback decision ([ADR-005](../adr/ADR-005-vivo-package-identity-and-oauth-resolution.md)).
- `PlatformTokenStore` (`expect/actual`) persists `(UserProfile, accessToken, refreshToken, expiresAtEpochMs)` across Android (`AndroidKeyStore` AES-GCM), JVM (`0600` `~/.streamify/auth_session.json`), iOS (`NSUserDefaults`), and Wasm (`localStorage`), and `SupabaseClient` persists rotated refresh tokens after every `refreshSession()`, `exchangeCodeForSession()`, and `signInWithGoogleIdToken()` call.
- `SupabaseClient.ensureValidAccessToken()` checks `AuthRefreshMutex.isTokenExpired()` proactively before `SupabaseLibrarySync` requests; `wasmJsMain` `init()` handles `?code=` / `#access_token=` callbacks and cleans the URL via `history.replaceState`, and `iosMain` launches `UIApplication.sharedApplication.openURL` with `handleIosAuthCallback` in `MainViewController.kt`.
- `SupabaseConfig.DEFAULT_USER.isAdmin` is `false`; `PkceCrypto` uses `secureRandomBytes` CSPRNG (`SecureRandom` / `arc4random_buf` / `crypto.getRandomValues`); `handleAuthCallback` fails closed on missing/mismatched CSRF `state` and rejects implicit fragment `access_token`s; `signOut` invokes best-effort `SupabaseClient.signOutRemote` and `setSession` clears prior `refreshToken`s when omitted.
- `Ed25519ManifestSignatureVerifier` on iOS and Wasm fails closed (`false`) until native CryptoKit / WebCrypto verification is implemented.

## Interfaces

- Login → PKCE exchange → session → RLS-scoped RPCs.

## Decisions and specs

- Spec: [CRYPTO_SPEC.md](../architecture/CRYPTO_SPEC.md)
- Spec: [12_server_security.md](../architecture/engines/12_server_security.md)
- Decision: [ADR-005-vivo-package-identity-and-oauth-resolution](../adr/ADR-005-vivo-package-identity-and-oauth-resolution.md)

## Known limits / debt

- Crypto spec predates the retired Rust core; verify its current applicability.

## Refinement backlog

- Add an auth-flow sequence diagram to the playbook.

<!-- doc-verified-against: 10f009ccdfbe820499ddeb50f2cf55426307747b -->
