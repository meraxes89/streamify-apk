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

## Secret rotation runbook (SEC-01 / SEC-10a)

A `service_role` Supabase JWT is part of git history (flagged by SEC-01,
**presumed compromised**). Rotation happens in the Supabase dashboard —
no repository change can perform or verify it — so the repo carries the
runbook and the audit trail instead:

1. **Rotate:** Supabase dashboard → Settings → API → *Reset* the
   `service_role` key. Update every consumer of the old key (CI secrets,
   edge tooling, local `.env` files) — the repo itself does not
   reference it.
2. **Prove it:** call `GET {SUPABASE_URL}/auth/v1/user` with the OLD key
   in the `apikey` header. Expected result after rotation: **401**.
   Record the check (date, result, who ran it) in the table below.
3. **Never re-introduce:** gitleaks runs blocking on every PR/push to
   `main` (preflight); the allowlist covers exactly one public
   InnerTube web-client identifier (see `.gitleaks.toml`, SEC-07).

| Date | Check | Result | By |
|---|---|---|---|
| *(pending — requires dashboard access)* | old service_role key rejected | — | lead maintainer |

### Credential inventory (post-H0)

| Credential | Where it lives today | Status |
|---|---|---|
| ZhipuAI keys (5) | **burned** — removed from Kotlin (SEC-08); must be revoked at the provider console | repo: clean · revocation: **lead action pending** |
| InnerTube web-client key | `.gitleaks.toml` allowlisted public constant (SEC-07) | documented, non-billable |
| Supabase URL / anon key / OAuth client ID | `app/build.gradle.kts` BuildConfig defaults | public-by-design (RLS is the fence, SEC-10) |
| service_role key | git history only (SEC-01) | **rotation pending — see runbook above** |
| `GEMINI_API_KEY` | repo-level secret (the only one) — consumed solely by `gemini.yml`, never by a release lane | out of release scope by construction (GOV-05) |
| Signing secrets (`RELEASE_KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) | **`release` GitHub environment** (GOV-05): reviewer-gated, deployable only from `v*` tags — no PR lane, branch or scheduled job can read them | **lead action pending: enter the 4 values in the environment** — they were never present at repo level, and the Secrets API is write-only (values cannot be migrated by an agent) |

#### Release environment runbook (GOV-05)

The Android release lane (`release.yml` tag push → `_ci-android.yml` `release_mode: true`) uses
exactly four credentials — the SEC-04 keystore decode (`RELEASE_KEYSTORE_B64`) and the three
`assembleRelease` signing parameters (`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). All four
now live behind the protected `release` environment: a reviewer must approve each deployment, and
the environment only accepts `v*` tags. To finish the migration (one-time, repo admin):

1. GitHub → repo **Settings → Environments → release → Add secret** — create the four secrets with
   the exact names above and the real keystore values.
2. Confirm no signing secret exists at **Settings → Secrets and variables → Actions** (repo level
   must stay empty of signing material — that is the whole point: `secrets: inherit` from
   `release.yml` can then never leak a signing secret to a non-release lane).
3. Prove it: push a `v*` tag — the assemble job pauses for reviewer approval, then signs; a
   deliberate push of the same workflow from any branch shows `RELEASE_KEYSTORE_B64` unresolved.
