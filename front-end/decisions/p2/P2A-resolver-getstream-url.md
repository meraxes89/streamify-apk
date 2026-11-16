# P2-A — Resolver stream: `getStreamUrl` + the link-solver lock

**Status: IN REVIEW** — 2026-11-17, Engineer A (P2 driver). Branch
`p2a/resolver-getstreamurl` (this PR). The contract this implements:
`P2A-contract-getstream-url.md` (posted week one, per the kickoff).

## Scope (this PR — the resolver stream only)

- `front-end/web/src/audio/get-stream-url.ts` — the single playback entry
  point. Wraps `resolveAudioStream`; per-track cache + in-flight dedup;
  expiry from the wire; ranked element ladder; range probe; instrumentation.
  **Zero player code** — the app stream (feishin side) is a separate PR.
- `resolve.ts` — one additive seam: `ResolveContext.onLadderDigest`. A
  winning rung hands its raw materials (the response formats, the wire
  expiry, the protocol family) to the caller's sink. Inert when absent:
  the link solver's behavior is unchanged — locked by the new fixtures.
  Internal threading only (`runRawRung`/`evaluateRawResponse` carry the
  sink); no existing export's signature or behavior changed.
- First-ever test lane for `front-end/web` (vitest, network-free by
  construction): `src/audio/__tests__/` — the fixture lock.
- CI: the fixture lock runs on every `front-end/**` PR (`web-build.yml`);
  the daily SABR corpus runs in `resolver-canary.yml` (`sabr-corpus` job).
- `streamify-web` minor version bump `0.0.0 → 0.1.0`.

## The same-day flag the kickoff asked for (expiry / range metadata)

**The pipeline did not carry either.** `ResolvedStream` has no expiry field
(the wire's `expiresInSeconds` existed but was never read in) and no range
hint (range support was only discovered reactively via 403s). Both facts
were flagged the day of the kickoff per the "same-day" rule. The fix is
additive and shipped here: `getStreamUrl` surfaces both — expiry read from
the wire through the digest (6 h documented default when silent), range
support probed once per resolution via `ChunkFetcher.probe` (1-byte Range
request, direct). `ResolvedStream` itself is unchanged; the six legacy
importers are untouched and the fixtures prove it.

## Design notes worth keeping

- **The digest fires pre-`&pot=`-pinning** (inside `evaluateRawResponse`);
  `getStreamUrl` re-pins every ladder rung uniformly (`pinUrl`, best-effort
  GVS `&pot=`), so the top rung and the walk rungs stay consistent —
  including the pipeline's own pick when it lands mid-ladder.
- **The element ladder re-ranks the pipeline's pick.** The solver's element
  posture is deliberately conservative (AAC-first, W2.0); the ladder puts
  opus/webm above it so capability mismatches degrade at runtime (walk)
  instead of failing up front. The DoD test for the walk ("force rung 0 to
  403, playback continues on rung two") is proven in the app-stream PR's
  tests; the resolver-side primitives (`probeStreamUrl` classifying
  403 vs healthy) are locked here.
- **SABR wins carry no element ladder.** The digest marks the protocol; the
  instrumentation counts the hit; the daily corpus is the decision data for
  the custom-engine checkpoint (one week, then a joint call — recorded in
  the contract note). Nobody builds the custom engine speculatively.
- **The corpus** is the de-facto W1.2 corpus: the canary/g3b canonical IDs
  (`dQw4w9WgXcQ`, `4NRXx6U8ABQ`, `tixIb21MO5U`, `DOGP2dYUGE0`). Report:
  `sabr-report.json` artifact (90-day retention) + `$GITHUB_STEP_SUMMARY`
  table. A SABR-heavy day is data; only zero-resolves gates red (infra).

## The fixture lock (the guardrail)

`p2a.fixtures.json` holds recorded wire shapes (the canary/probe lineage);
the two suites run the REAL ladder end-to-end against them with every
network call routed through a test router (`__p2a`). Locked: rung
sequencing, the G4.3 retry discipline, verdict aggregation, ledger
escalation, terminal diagnosis (bot-gate marker), stream mapping for every
rung family (visionos/ios/ios-pot/relay/web-safari raw rungs, the WEB+POT
progressive + HLS + SABR paths), and `getStreamUrl`'s cache/expiry/probe/
ranking/instrumentation behavior. **22 tests, all green locally
(typecheck + vitest); CI runs them on every `front-end/**` PR.**
Fixtures are append-only — existing expectations are never edited.

## Handoff to the app stream (PR 2, same phase)

The feishin side consumes `getStreamUrl` through a vendored mirror of the
resolver stack (9 files, byte-identity enforced by CI — see that PR's
record). Engine wiring, seek strategy, ladder walk, expiry re-resolve and
the SABR honest state live there, in new fork directories plus thin
PATCHES-able seams. The two streams never cross-wire: resolution fixes land
HERE, in this tree, always.
