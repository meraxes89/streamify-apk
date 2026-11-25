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

- Client-side PKCE with a loopback server on desktop; Vivo has a dedicated callback decision.

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

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
