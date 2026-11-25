# scripts/ — Verification harnesses and developer tools

Developer-side harnesses; not shipped. Four of them gate CI (see the wiring
table). Run `python3 scripts/<name>.py --help` where applicable.

## Inventory

| Script | Purpose |
|---|---|
| verify_docs.py | Documentation gate: markdown link integrity across the repo plus `doc-verified-against` staleness pins via `git merge-base --is-ancestor` |
| docs_ledger_check.py | Tier-1 CI lint: workflow refs, release tags, relative links in curated docs, stale-claim denylists, ledger checkbox paths |
| check_agent_skills.py | Verifies the six vendored Ponytail skills and the OpenCode/Claude command copies; no mirror, because OpenCode also scans `.agents/skills` |
| jni_parity_check.py | JNI ABI parity tripwire. `--source`: every Kotlin `external fun` has a `Java_*` export and no ghost exports (handles name mangling incl. `com.streamify.core.native_.NativeAudioSink`). `--nm`: diffs exports against the built .so with llvm-nm |
| jni_panic_boundary_check.py | Every `Java_*` export in Rust JNI TUs calls `catch_unwind` in-body; C++ TUs brace-match `JNI_SHIELD`. Exemptions only via `// panic-boundary-exempt: <reason>` |
| check_feishin_resolver.py | Vendored resolver integrity (P5-A): file existence, provenance headers, self-contained imports, `getStreamUrl` contract |
| generate_lufs_golden_vectors.py | BS.1770-4 golden vectors pinning Rust `lufs.rs` and C++ `LufsNormalizer.cc` to the same neutral f64 reference |
| generate_jam_golden_vectors.py | 48-byte jam CRDT op wire fixtures pinning Kotlin and Rust encoders byte-for-byte |
| generate_playback_fixtures.py | Deterministic WAV fixtures (440 Hz f32 stereo, 48k/44.1k) for decode/selftest suites |
| build-wasm-dist.sh | `:composeApp:wasmJsBrowserDistribution`, copies to `server/public/`, gzip -9 precompression |
| build_and_sign_apk.sh | Release APK build + zipalign + `apksigner verify --verbose`; `--vivo` builds the vivo variant; keystore via env |
| build_ios_xcframework.sh | Assembles `StreamifyCore.xcframework` (4 Apple slices) from the Rust staticlib + ObjC++ glue + UniFFI Swift module |
| wrap-mac-dmg.sh | Conveyor .app -> polished .dmg (quarantine-class fix, create-dmg pipeline) |
| gauntlet_v2.py, extreme_extract_test.py, stage1_extract.py, gvs_binding_test.py, curl_probe.sh | YouTube resolver probes (client matrix, extraction gauntlet, sig+n transform smoke, ANDROID_VR session binding, Termux probes) |
| mpv-linux/ | Dockerfile + stage.sh building `libmpv.so.2` against glibc 2.35 for JVM dlopen |
| remote-config/ | Live greeting config (greetings.json) served from the raw CDN |
| windows/install.bat | Offline MSIX installer (self-elevating, cert install, x64-only) |

## CI wiring

| Gate | Lane | Enforces |
|---|---|---|
| docs_ledger_check.py | ci.yml Tier-1 | Doc reality vs tree |
| verify_docs.py | ci.yml Tier-1 | Markdown link targets & doc-verified-against staleness pins |
| jni_panic_boundary_check.py | ci.yml Tier-1 | Panic shields on every JNI export |
| check_feishin_resolver.py | ci.yml Tier-1 | Vendored resolver integrity |
| jni_parity_check.py --source / --nm | _ci-android.yml | Kotlin/native ABI parity, fail-fast then on the built .so |

Tier-1 gates are toolchain- and network-free (pure Python) so every PR pays
seconds, not minutes, for them.

## Documentation staleness protocol

`verify_docs.py` implements the protocol from `docs/adr/README.md`:

```bash
python3 scripts/verify_docs.py
```

It scans all markdown outside `legacy/`, `.claude/`, `node_modules/`, and
build outputs; verifies every repo-relative link target exists; and for each
`<!-- doc-verified-against: <sha> -->` pin checks (a) the pinned commit is an
ancestor of `origin/main` — a pin pointing outside integration history is an
error — and (b) reports documents whose pin is older than the tip of
`origin/main` as stale. Exit code 0 only when there are no errors.

---
Verified against commit 7c60ae82. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 7c60ae82b6459f17876bcdb5d0f168e44c816c5b -->
