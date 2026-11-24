# Streamify Remote Announcements & Launch Popup Control

This directory hosts the remote announcements manifest consumed by Streamify clients via GitHub Raw CDN:

```
https://raw.githubusercontent.com/meraxes89/streamify-apk/main/docs/announcements/manifest.json
```

---

## Remote Launch Popup Control

The launch popup dialog is governed by a master boolean statement:

```json
"popupModalEnabled": false
```

- **Disable Launch Popups (`false`)**: When set to `false`, no announcement modal is allowed to pop up on cold launch across any deployed client worldwide. Any active modal on screen is immediately dismissed, and local fallback defaults are suppressed.
- **Enable Launch Popups (`true`)**: When set to `true`, the client evaluates candidate announcements against frequency caps and platform targeting, presenting the highest priority eligible item.

---

## Markdown Formatting

The `content.body` field supports GitHub Flavored Markdown:
- Bold: `**text**`
- Italic: `*text*`
- Unordered lists: `- item`
- Headers: `# Header`

---

## Security Governance

- **Ed25519 Cryptographic Verification**: The `signatureHex` field must contain a 128-character hex string signature validating the payload against the pinned release public key.
- **Deep Link Allowlist**: Any action URL must point to approved domains (`github.com/meraxes89`, `discord.gg`, `x.com/sireenyadav`, `streamify.app`).
