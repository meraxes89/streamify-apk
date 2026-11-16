#!/usr/bin/env bash
# build-feishin-cipher.sh — deterministic staging of the P4-A cipher wasm.
#
# The feishin fork consumes a COMPACT, cipher-only build of
# front-end/crates/streamify-wasm (the XChaCha20-Poly1305 downloads chunk
# cipher — see src/cipher.rs). This script is the only thing that writes
# the vendored copy in the feishin tree:
#
#   front-end/feishin/src/renderer/api/streamify/wasm/streamify_cipher_bg.wasm
#
# plus its manifest (artifact.json: sha256 + toolchain pins). Regenerate
# both in the same PR whenever src/cipher.rs or the toolchain changes.
# Feishin CI verifies the pair — the vitest manifest test fails if the
# committed bytes and the committed manifest disagree, and the cipher
# contract tests run against the actual committed artifact.
#
# Requirements: rust (wasm32-unknown-unknown target) and wasm-bindgen-cli
# 0.2.130 (pinned to front-end/Cargo.lock — bump both together).
#
# Usage (from front-end/):
#   bash crates/streamify-wasm/build-feishin-cipher.sh          # stage
#   bash crates/streamify-wasm/build-feishin-cipher.sh --check   # verify only
set -euo pipefail

cd "$(dirname "$0")/../../"   # front-end/

CRATE=crates/streamify-wasm
OUT="$CRATE/pkg-cipher"
DEST="feishin/src/renderer/api/streamify/wasm"

CHECK=0
if [[ "${1:-}" == "--check" ]]; then CHECK=1; fi

echo "== cipher wasm: cargo build (release, --no-default-features --features cipher)"
cargo build --release --target wasm32-unknown-unknown \
    -p streamify-wasm --no-default-features --features cipher

echo "== wasm-bindgen (web target)"
rm -rf "$OUT"
wasm-bindgen --target web --out-dir "$OUT" \
    target/wasm32-unknown-unknown/release/streamify_wasm.wasm

echo "== stage into feishin"
mkdir -p "$DEST"
cp "$OUT/streamify_wasm_bg.wasm" "$DEST/streamify_cipher_bg.wasm"

echo "== manifest"
RUSTC_V=$(rustc --version | awk '{print $2}')
WB_V=$(wasm-bindgen --version | awk '{print $2}')
SHA=$(sha256sum "$DEST/streamify_cipher_bg.wasm" | awk '{print $1}')
SIZE=$(stat -c%s "$DEST/streamify_cipher_bg.wasm")
cat > "$DEST/artifact.json" <<EOF
{
  "format": "streamify-cipher-wasm-manifest/v1",
  "artifact": "streamify_cipher_bg.wasm",
  "crate": "front-end/crates/streamify-wasm",
  "features": "--no-default-features --features cipher",
  "profile": "release",
  "target": "wasm32-unknown-unknown",
  "wasm-bindgen": "${WB_V}",
  "rustc": "${RUSTC_V}",
  "sha256": "${SHA}",
  "bytes": ${SIZE}
}
EOF
cat "$DEST/artifact.json"

if [[ "$CHECK" == "1" ]]; then
  ACTUAL=$(sha256sum "$DEST/streamify_cipher_bg.wasm" | awk '{print $1}')
  EXPECTED=$(python3 -c "import json;print(json.load(open('$DEST/artifact.json'))['sha256'])")
  if [[ "$ACTUAL" != "$EXPECTED" ]]; then
    echo "MANIFEST DRIFT: staged bytes do not match artifact.json — re-run the build script" >&2
    exit 1
  fi
  echo "OK: staged cipher artifact matches its manifest"
fi
