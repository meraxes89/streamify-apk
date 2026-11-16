# `api/streamify/wasm` — the P4-A cipher wasm (generated territory)

This folder holds the vendored cipher-only build of
`front-end/crates/streamify-wasm` (the XChaCha20-Poly1305 downloads chunk
cipher). It is **generated territory** — never edit these files by hand.

## Contents

- `streamify_cipher_bg.wasm` — the compiled artifact (feature `cipher`,
  `--no-default-features`, release profile, ~35 KB). One import only
  (wasm-bindgen 0.2.130's externref table initializer, provided by hand
  in `cipher-loader.ts` — no wasm-pack glue JS is vendored).
- `artifact.json` — the build manifest: sha256, rustc and wasm-bindgen
  pins, feature flags, byte size. `__tests__/wasm-artifact.test.ts`
  verifies the committed bytes against it; the cipher contract tests run
  against the actual committed artifact, so hand-edits or stale manifests
  fail Feishin CI.
- `cipher-loader.ts` — the hand-written typed loader (ours, linted; the
  .wasm itself is binary and ignored by eslint/tsc).

## Regenerating

From `front-end/` (requires rust + wasm32-unknown-unknown +
wasm-bindgen-cli 0.2.130 — pinned to `front-end/Cargo.lock`):

```sh
bash crates/streamify-wasm/build-feishin-cipher.sh
```

The script builds, stages the artifact here, and rewrites `artifact.json`
in the same run. Rebuilds are deterministic (byte-identical for the same
toolchain); regenerate the manifest in the same PR whenever
`crates/streamify-wasm/src/cipher.rs` or the toolchain changes.
