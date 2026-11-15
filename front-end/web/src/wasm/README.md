# `src/wasm` — wasm-pack facade output (generated territory)

This folder holds the wasm-pack facade output for the Rust engine
(`front-end/crates/streamify-wasm`). It is **generated territory** — never
edit these files by hand.

## P0 state: the real output is committed

For the proof phase the **real generated output is committed** (the 3.3 MB
`.wasm` + glue + `.d.ts`) so a fresh checkout always builds and deploys a
working proof console, independent of the Rust toolchain. `CI
(.github/workflows/web-build.yml)` and the deploy workflow regenerate the
pkg and overwrite these files before every build — committed output that
drifts from the crate fails CI's own build, not silently.

## Generating

From `front-end/` (requires rust + `wasm32-unknown-unknown` + wasm-pack 0.15,
wasm-bindgen-cli matching the lockfile — see web-build.yml):

```sh
wasm-pack build crates/streamify-wasm --target web --out-dir pkg --no-opt
cp crates/streamify-wasm/pkg/streamify_wasm.js \
   crates/streamify-wasm/pkg/streamify_wasm.d.ts \
   crates/streamify-wasm/pkg/streamify_wasm_bg.wasm \
   crates/streamify-wasm/pkg/streamify_wasm_bg.wasm.d.ts \
   web/src/wasm/
```

`--no-opt`: the wasm-pack-bundled wasm-opt (binaryen) rejects the bulk-memory
ops rustc emits — see the crate's `Cargo.toml` note.

## Module shape (the bridge contract consumed by `src/lib/engine.ts`)

- `export default init(): Promise<void>` — instantiate the wasm module
- `ping(): string`
- `set_relay_base_url(url: string): void`
- `search_music(query: string): Promise<string>` — JSON string of `Track[]`
- `resolve_stream(videoId: string): Promise<string>` — JSON string of stream info
