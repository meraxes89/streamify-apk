# Security Policy

## Scope

Streamify is an Android client (Kotlin + Jetpack Compose) with native engines (C++20 DSP, Rust core), a peer-to-peer jam mesh (CRDT ops, leaderless election, gossip), and an optional Supabase backend layer. Security-relevant surfaces include:

- **Native boundary:** JNI contracts between ART and `.so` libraries (`app/src/main/java/com/streamify/app/data/NativeBridge.kt`, `native/jni/`, `rust/src/jni_bridge*.rs`). Memory-safety bugs here are treated as high severity.
- **P2P protocol:** Jam wire format (`jam/JamOpWire.kt`), Merkle reconciliation, election safety. Split-brain or forged-op conditions are high severity.
- **Backend:** Supabase schemas/RPCs (`supabase/`), anon-key scoping, RLS policies.
- **Secrets handling:** API keys sourced from `local.properties`/env into `BuildConfig`. Any committed credential is a reportable finding.
- **CI/CD:** Workflow permissions, release pipeline integrity.

## Reporting a Vulnerability

**Do not open a public issue for security problems.**

- Report privately via GitHub: open a **Draft Security Advisory** at `https://github.com/meraxes89/streamify-apk/security/advisories/new` if you are a collaborator, or email the maintainers directly (address listed in your GitHub → repo → Settings → Collaborators; otherwise open a minimal issue titled `contact-request` and a maintainer will reply with a secure channel).
- Include: affected component/file, reproduction steps or proof of concept, potential impact, and any suggested fix.

## What to expect

| Stage | Target SLA |
|---|---|
| Acknowledgment of report | **72 hours** |
| Initial severity assessment (CVSS v3.1) | 7 days |
| Fix or mitigation plan shared with reporter | 14 days |
| Coordinated disclosure window before public advisory | up to 90 days, reporter-coordinated |

Severity triage follows CVSS v3.1 with these local conventions:

- **Critical:** remote code execution across JNI/FFI, consensus fork enabling unauthorized host control, exposure of user data or signing material.
- **High:** denial of service on playback/mesh paths reachable from untrusted network input, RLS bypass, secret leakage in published artifacts.
- **Medium:** local privilege issues, downgrade of transport protections, telemetry over-collection.
- **Low:** hardening opportunities, defense-in-depth gaps.

## Out of scope

- Findings requiring physical device access with an already-unlocked bootloader.
- Third-party upstream service outages (YouTube InnerTube changes, etc.) — these are tracked as operational issues, not vulnerabilities.
- Social engineering of end users.

## Bounties

Streamify is a free platform maintained by volunteers. We currently offer **attribution in the GitHub Security Advisory** and lifetime gratitude, not monetary bounties. If you can sponsor the program, raise it in a private report — we are listening.

## Hardening in progress (public tracker)

Known structural risks we are actively working through are tracked in the public audit issue (JNI ABI parity checks, dual Jam-stack differential testing, blocking CI gates, protected `main`). See [README](README.md) → docs/ for engine manuals.
