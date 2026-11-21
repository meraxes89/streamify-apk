# Streamify Dynamic Remote Config

This directory houses the live remote configuration files consumed by Streamify clients over Fastly/Cloudflare CDN via `raw.githubusercontent.com`.

---

## Live Endpoint

```
https://raw.githubusercontent.com/meraxes89/streamify-apk/main/scripts/remote-config/greetings.json
```

---

## `greetings.json` Schema

The greeting engine determines which greeting to display based on the user's local hour (`0..23`) or active calendar date.

### Properties

| Field | Type | Description |
|---|---|---|
| `version` | `Integer` | Configuration schema version for forward compatibility. |
| `slots` | `Array<Slot>` | Time-of-day slots with start/end hours and message arrays. |
| `special_events` | `Array<Event>` | Date-based holiday or special overrides (checked before hourly slots). |

### Slot Structure

- `id`: Unique identifier (e.g., `morning`, `afternoon`, `evening`, `late_night`).
- `start_hour`: Inclusive start hour (`0..23`).
- `end_hour`: Inclusive end hour (`0..23`). Supports wrapping past midnight (e.g. `start_hour = 22`, `end_hour = 4`).
- `messages`: Map of language codes (`en`, `default`) containing an array of randomized phrases. When multiple phrases exist, the app selects one at runtime to keep greetings dynamic.

---

## Updating Greetings

1. Edit `greetings.json` directly on GitHub or in a pull request.
2. Merge into `main`.
3. All Streamify app instances worldwide will automatically pick up the new greetings in the background without requiring an app store update or rebuild.
