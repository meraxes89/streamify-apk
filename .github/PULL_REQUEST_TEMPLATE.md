<!--
One purpose per PR. Fill every section — "N/A" is fine where honest, empty is not.
Delete this comment before submitting.
-->

## What & why

**Summary:** One paragraph: what this PR changes and the problem it solves.

**Related issue:** Closes #

**Audit/philosophy link:** Which finding (e.g., #44 P1-2) or principle (PHILOSOPHY.md §…) does this serve?

## Type of change

- [ ] Bug fix (behavior corrected)
- [ ] Performance improvement (**measurement gate required below**)
- [ ] New feature (issue approved first)
- [ ] Refactor (no behavior change)
- [ ] Native boundary change (JNI exports / Rust FFI / wire format)
- [ ] CI / build / tooling
- [ ] Documentation only

## Boundary checklist (delete if truly none touched)

- [ ] Changed `external fun` ↔ native export parity verified (`NativeBridge.kt` vs `native/jni/*.cc` + `rust/src/jni_bridge*.rs`)
- [ ] Jam wire bytes bit-identical across Kotlin (`JamOpWire.kt`) and Rust (`jam_crdt.rs`) — golden vectors updated on both sides
- [ ] No new unguarded `unwrap()`/`expect()` in production Rust hot paths; no panic-across-JNI path
- [ ] Supabase migration reviewed for RLS/anon-key scoping (if `supabase/` touched)

## Tests

- [ ] Unit tests added/updated: `./gradlew testDebugUnitTest`
- [ ] Rust suite: `cargo test -p streamify-core`
- [ ] Native/DSP suites run (if applicable)
- List what covers the changed behavior: …

## Measurement gate (required for perf PRs)

| Metric | Before | After | Device/SoC | Runs | Variance |
|---|---|---|---|---|---|
|  |  |  |  |  |  |

Harness used (bench target / simpleperf / macrobenchmark): …

## Docs & changelog

- [ ] Folder README updated
- [ ] Engine manual (`docs/0X_…`) updated
- [ ] `CHANGELOG.md` entry added

## Screenshots / recordings

For UI changes only. Otherwise N/A.

## Risk & rollback

Blast radius if wrong: … Rollback plan: revert commit / flag off / …

## Author checklist

- [ ] Branch follows `<type>/<issue>-<slug>` and commit messages follow conventional commits (CONTRIBUTING.md)
- [ ] Local build green (`assembleDebug`)
- [ ] No secrets, tokens, or release keystores committed
- [ ] Diff is single-purpose (no drive-by reformatting mixed with behavior changes)
- [ ] Self-reviewed the full diff line by line
