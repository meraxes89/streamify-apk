# `src/wasm-engine` — wasm-pack facade output (generated territory)

This folder holds the wasm-pack output for the W2.1 engine control plane
(`front-end/crates/streamify-engine-wasm`). It is **generated territory** —
never edit these files by hand.

## Committed real output (P2 state)

Like `src/wasm/`, the real generated output is committed so a fresh checkout
always builds a working app, independent of the Rust toolchain. CI
(`web-build.yml`, the `wasm` job) regenerates the pkg and overwrites these
files before every build — committed output that drifts from the crate fails
CI's own build, not silently.

## Generating

From `front-end/` (requires rust + `wasm32-unknown-unknown` + wasm-pack 0.15,
wasm-bindgen-cli matching the lockfile — see web-build.yml):

```bash
wasm-pack build crates/streamify-engine-wasm --target web --out-dir pkg --no-opt
cp crates/streamify-engine-wasm/pkg/streamify_engine_wasm.{js,d.ts} \
   crates/streamify-engine-wasm/pkg/streamify_engine_wasm_bg.wasm \
   crates/streamify-engine-wasm/pkg/streamify_engine_wasm_bg.wasm.d.ts \
   web/src/wasm-engine/
```

The crate's semantic contract and the TS wrapper live in
`src/lib/wasmEngine.ts` and the crate docs; the experiment record that
motivated the facade is `front-end/decisions/W2.0-engine-egress.md`.
