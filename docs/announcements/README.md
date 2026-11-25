# Streamify Remote Announcement & Launch Popup Guide

This directory houses the live remote announcement manifest consumed by all Streamify client applications (Android, Desktop, and WASM) via GitHub Raw CDN.

---

## 1. Endpoint & Live Distribution

```
https://raw.githubusercontent.com/meraxes89/streamify-apk/main/docs/announcements/manifest.json
```

- **Transport**: Fetched over HTTPS via Ktor HTTP client on cold launch.
- **Caching**: Client enforces HTTP ETag / 304 Not Modified caching. When the manifest has not changed on GitHub, the server responds with 304 and 0 payload bytes.
- **Offline / Degradation Policy**: If GitHub is unreachable (offline, DNS failure, 404), the client silently falls back to its local DataStore cache. If no cache exists, it fails closed to silence without crashing or blocking the UI.

---

## 2. The Master Launch Toggle: `popupModalEnabled`

The top-level boolean property `popupModalEnabled` controls whether *any* announcement popup dialog is permitted to appear on app launch worldwide:

```json
{
  "version": 1,
  "popupModalEnabled": false,
  ...
}
```

- **Set to `false`**: Instantly disables and suppresses all launch announcement modals across all deployed apps. Even if announcements are listed in the payload or local defaults exist, the engine will never present a modal dialog. Any active dialog currently on screen will be dismissed immediately.
- **Set to `true`**: Permits the engine to evaluate candidate announcements against targeting, frequency caps, and priority, showing the top eligible message.

---

## 3. Manifest Schema Reference

### Root Fields

| Field | Type | Description |
|---|---|---|
| `version` | `Integer` | Schema revision number (currently `1`). |
| `popupModalEnabled` | `Boolean` | Master toggle governing launch popup presentation. |
| `governance` | `Object` | Frequency budget, emergency thresholds, and allowlist configuration. |
| `issuedAtEpochMs` | `Long` | UTC Unix epoch millisecond timestamp when the manifest was generated. |
| `signatureHex` | `String` | 128-character hexadecimal Ed25519 cryptographic signature. |
| `revokedIds` | `Array<String>` | Emergency kill switch list. Any announcement ID in this array is immediately silenced and permanently purged from client caches. |
| `announcements` | `Array<Announcement>` | Array of announcement objects evaluated in order of priority. |

---

### Governance Configuration Object (`governance`)

Governs global pacing, emergency bypass criteria, and external domain trust:

| Field | Type | Default | Description |
|---|---|---|---|
| `globalCooldownMs` | `Long` | `86400000` (24h) | Minimum quiet period between any modal dialogs across the app. Coerced to safe range [0, 365 days]. |
| `emergencyPriorityThreshold` | `Integer` | `500` | Minimum priority score required to bypass `globalCooldownMs` and display immediately. |
| `defaultSnoozeCooldownMs` | `Long` | `604800000` (7d) | Default snooze cooldown when tapping "Later" if the announcement omits `frequency.cooldownMs`. |
| `allowedExtraDomains` | `Array<String>` | `[]` | List of cryptographically authenticated hostnames or prefixes dynamically added to `DeepLinkAllowlist`. |

---

### Announcement Object

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique message identifier (e.g., `streamify_v1_release`). |
| `schemaVersion` | `Integer` | Internal announcement schema version (default `1`). |
| `priority` | `Integer` | Higher numbers take precedence. Announcements with priority `>= 500` bypass the standard 24-hour global cooldown. |
| `presentation` | `String` | Presentation surface: `"MODAL"` (launch dialog) or `"INBOX"` (What's New screen only). |
| `content` | `Map<String, Content>` | Map of locale codes (`"en"`, `"fr"`, `"es"`) to localized content objects. |
| `action` | `Object` | Action triggered by the primary button. |
| `targeting` | `Object` | Environment and device eligibility filters. |
| `frequency` | `Object` | Impression capping and snooze intervals. |
| `dismissPolicy` | `String` | `"LATER_SNOOZE"` (shows "Later" button) or `"PERMANENT"` (one-time show). |
| `startAtEpochMs` | `Long` | Start time in UTC epoch ms (default `0`). |
| `endAtEpochMs` | `Long` | Expiration time in UTC epoch ms (default `9223372036854775807`). |

---

### Localized Content Object

```json
"en": {
  "title": "Welcome to Streamify",
  "body": "Experience sovereign lossless audio and synchronized lyrics.",
  "bannerUrl": "https://example.com/banner.png",
  "actionLabel": "Explore Releases",
  "contentDescription": "Streamify banner illustration"
}
```

- **Markdown Support in `body`**: Full GitHub Flavored Markdown is supported natively:
  - Headers: `# Heading`, `## Subheading`
  - Lists: `- Item 1\n- Item 2`
  - Emphasis: `**bold**`, `*italic*`
  - Code spans: `` `monospace` ``
- **Banner Image**: Recommended aspect ratio is 16:9. Nullable.
- **Locale Resolution**: The app matches the user's language code (e.g. `es-ES` -> `es` -> `en` -> first available).

---

### Action Types

#### DeepLink
Opens approved external URLs in the platform browser or handler:
```json
"action": {
  "type": "com.streamify.announcements.AnnouncementAction.DeepLink",
  "url": "https://github.com/meraxes89/streamify-apk"
}
```
> [!IMPORTANT]
> To prevent phishing attacks, URLs are strictly checked against `DeepLinkAllowlist`. Only approved domains are allowed:
> - `github.com/meraxes89` (and subpaths)
> - `discord.gg`
> - `x.com/sireenyadav` (and subpaths)
> - `streamify.app`

#### None
Informational dialog with dismiss button only:
```json
"action": {
  "type": "com.streamify.announcements.AnnouncementAction.None"
}
```

---

### Targeting Filter Object

```json
"targeting": {
  "minVersion": "1.0.0",
  "maxVersion": "2.0.0",
  "platforms": ["android", "desktop"],
  "locales": ["en", "de"]
}
```

- `platforms`: Empty list `[]` targets all platforms. Otherwise accepts `"android"`, `"desktop"`, `"wasm"`.
- `minVersion` / `maxVersion`: Semantic version boundaries (supports `v1.0.3` or `1.0.3`).
- `locales`: List of allowed ISO language prefixes. Empty list targets all locales.

---

### Frequency Governance Object

```json
"frequency": {
  "maxImpressions": 1,
  "cooldownMs": 604800000
}
```

- `maxImpressions`: Maximum number of times this announcement will be displayed to a user.
- `cooldownMs`: When `dismissPolicy` is `LATER_SNOOZE`, tapping "Later" silences the message for this duration (e.g. 7 days = `604800000` ms) before allowing it to appear again.
- **Global Annoyance Budget**: By default, clients display at most one announcement every 24 hours across the entire application, unless `priority >= 500`.

---

## 4. Engineer Operating Procedures

### Scenario A: Disable All Launch Popups Immediately
1. Open `docs/announcements/manifest.json`.
2. Set `"popupModalEnabled": false`.
3. Commit directly to `main` or merge a PR.
4. All connected devices globally will stop showing the launch popup on their next background sync.

### Scenario B: Publish a New Release Announcement
1. Open `docs/announcements/manifest.json`.
2. Set `"popupModalEnabled": true`.
3. Add a new announcement entry with a unique `id` and your Markdown copy in `content.en.body`.
4. Ensure the `action.url` complies with the `DeepLinkAllowlist`.
5. Sign the manifest using `node scripts/sign_manifest.js` to compute the authentic Ed25519 signature.
6. Commit and merge to `main`.

### Scenario C: Emergency Kill-Switch for a Malformed Announcement
If a previously deployed announcement contains a typo or broken asset:
1. Open `docs/announcements/manifest.json`.
2. Add the announcement's `id` to `"revokedIds": ["bad_announcement_id"]`.
3. Commit and merge to `main`.
4. The client will immediately drop and suppress that announcement from active display and from the user's inbox.

---

## 5. Cryptographic Verification & Manifest Signing Guide (P0-2)

### Architecture & Security Model
All Streamify clients (Android, Desktop, iOS, and Web/WASM) enforce authentic **Ed25519 asymmetric signature verification** on the remote announcement manifest before parsing or displaying any announcement content:
- **Pinned Release Public Key**: `e5eb74f63c8f8b3949826354f923c66f9de58f8e7b1e4277b7bf0e7f7b32cb64` (pinned in `RemoteSecurity.kt: PINNED_MANIFEST_PUBLIC_KEY_HEX`).
- **Signature Format**: 128-character hexadecimal Ed25519 signature stored in `manifest.json` under `"signatureHex"`.
- **Canonical Payload Binding**: Signing and verification are computed over a normalized canonical representation: the exact UTF-8 content of `manifest.json` with `"signatureHex": ""` (empty string), avoiding whitespace desynchronization or formatting artifacts.
- **Fail-Closed Behavior**: If an untrusted actor modifies the manifest or pushes an announcement without a valid Ed25519 signature from the pinned private key, the client rejects the payload, logs an error, and silently falls back to cached announcements.

### How to Sign docs/announcements/manifest.json
Whenever you edit, add, or remove announcements in `docs/announcements/manifest.json`, you must regenerate its signature before committing:

1. **Prerequisites**: Node.js v16+ (using native Node `crypto`).
2. **Execute the Signing Script**:
   ```bash
   node scripts/sign_manifest.js
   ```
   Or with a custom private key via environment variable:
   ```bash
   STREAMIFY_ANNOUNCEMENT_KEY="<64_hex_private_key>" node scripts/sign_manifest.js
   ```
3. **Verify the Output**:
   The script verifies the signature immediately after generation and updates `docs/announcements/manifest.json` in-place:
   ```
   Successfully signed manifest.json
   Public Key (hex): e5eb74f63c8f8b3949826354f923c66f9de58f8e7b1e4277b7bf0e7f7b32cb64
   Signature (hex): <128_hex_signature>
   ```
4. **Commit & Push**:
   Stage both the modified `manifest.json` and documentation changes, then commit to your release branch.

