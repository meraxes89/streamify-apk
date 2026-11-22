# Amazon Catalog Engine (Streamify Laboratory)

An autonomous, anonymous, on-device Amazon Music metadata engine developed in isolation from `/server/src`.

## Architecture & Principles

1. **Anonymous On-Device Ingestion:**
   * Handshakes against `https://music.amazon.{tld}/config.json` to acquire guest session credentials (`sessionId`, `deviceId`, `deviceType`, `csrf`).
   * No user login, no credentials, and no developer API access token required.
2. **Decoupled Protocol Descriptor (`protocol.json`):**
   * Isolates volatile Amazon headers, target names, and endpoints from stable client logic.
   * Can be refreshed on-the-fly without rebuilding application code.
3. **Multi-Territory Support:**
   * Manages independent guest sessions across marketplaces: US (`NA`), GB (`ZAZ`), IN (`ZAZ`), DE (`ZAZ`), JP (`FE`).
4. **Governance & Anti-Ban Protections (`src/governance.mjs`):**
   * Single-flight session initialization (prevents concurrency stampedes).
   * Per-region circuit breaker that trips immediately on HTTP 429 with exponential backoff and cooldown quarantine.
   * Rate pacer ensuring non-bursty residential egress.
5. **Canonical Normalization (`src/mappers.mjs`):**
   * Standardizes raw ASINs into Streamify's canonical multiplatform schema (`amzn:track:{asin}`).
   * Extracts track title, artists, duration, scaled artwork, release year, and record label (℗ line) for hybrid matching to YouTube playback.
6. **Graceful Degradation (`src/scraper.mjs`):**
   * Tier-1: Internal Coral RPC
   * Tier-2: SSR Hydration State (`__NEXT_DATA__`)
   * Tier-3: Schema.org `MusicRecording` HTML microdata

---

## Quick Start & Verification

Run tests:
```bash
cd amazon-catalog-engine
npm test
```

Run discovery probe across marketplaces:
```bash
node cli.mjs probe
```

Inspect anonymous guest session:
```bash
node cli.mjs session US
node cli.mjs session IN
```

Perform a catalog search:
```bash
node cli.mjs search "Bohemian Rhapsody"
```
