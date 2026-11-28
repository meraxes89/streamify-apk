# RFC 005: Signed Release Policy & Capability-Gated Updates (S20)

**Status**: Accepted (implemented in #372). See the [RFC index](README.md).  
**RFC ID**: 005  
**Tracking Issue**: [#372](https://github.com/meraxes89/streamify-apk/issues/372)  
**Authors**: Streamify Core Engineering  
**Scope**: `scripts/`, `.github/workflows/release.yml`, `composeApp/` (S20)  
**Target Modules**: `composeApp`, release scripts (zero new external dependencies)

---

## 1. Executive Summary

This RFC redesigns S20's update enforcement so that:
1. `version.json` is cryptographically signed with Streamify's existing Ed25519 release keypair (`STREAMIFY_ANNOUNCEMENT_KEY` / `PinnedManifestSignatureVerifier`).
2. A pure policy decision engine (`evaluateUpdatePolicy`) computes `UpdateUrgency`, remaining grace hours, and `isOnlineStreamingBlocked`.
3. `UpdateStateHolder` exposes a reactive `StateFlow<UpdatePolicyState>` rather than mutable process-global `var`s.
4. `PlatformUpdater.verifySha256()` rejects blank expected hashes instead of returning `true`.
5. Enforcement happens at the **online streaming capability boundary** (`SharedViewModel.loadMediaItemFromTrack`) when a **signed** policy reaches `ENFORCED`—while **offline downloaded tracks remain 100% playable** (zero-lockout guarantee).
6. Manual "Check for updates" in Settings passes `force = true` so users can always check even when `autoCheckForUpdates` is off.

---

## 2. Decisions

- **Signing key**: Reuse the existing Ed25519 announcement keypair (`RemoteSecurity.kt` / `PinnedManifestSignatureVerifier`). `scripts/sign_version_json.js` canonicalizes `version.json` by replacing `"signatureHex": "..."` with `"signatureHex": ""` before signing, matching `scripts/sign_manifest.js`.
- **Signature enforcement**:
  - Tampered or invalid `signatureHex` is rejected immediately (`UpdateResult.Error`).
  - Unsigned legacy `version.json` manifests (`signatureHex == ""`) may still prompt softly, but **cannot** activate `ENFORCED` capability gates (`isPolicyVerified == false`).
- **Delivery mechanism**: Keep the existing static `version.json` + 3-tier `UpdateResolver` architecture (no Supabase Realtime or FCM/APNs dependencies).
- **Capability gate scope**: Gate **online streaming only** when `isOnlineStreamingBlocked == true`; tracks with `DownloadState.STATE_DOWNLOADED` always play normally.
