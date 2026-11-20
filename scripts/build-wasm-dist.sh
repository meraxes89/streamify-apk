#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_SRC="${DIR}/composeApp/build/dist/wasmJs/productionExecutable"
PUBLIC_DEST="${DIR}/server/public"

echo "=== Building Compose Wasm Browser Distribution ==="
cd "${DIR}"
./gradlew :composeApp:wasmJsBrowserDistribution --no-daemon

if [ ! -d "${DIST_SRC}" ]; then
  echo "Error: Build output directory not found at ${DIST_SRC}"
  exit 1
fi

echo "=== Syncing artifacts to server/public/ ==="
mkdir -p "${PUBLIC_DEST}"
cp -rf "${DIST_SRC}"/* "${PUBLIC_DEST}"/

echo "=== Precompressing static assets (.gz) ==="
if command -v gzip >/dev/null 2>&1; then
  find "${PUBLIC_DEST}" -type f \( -name "*.js" -o -name "*.wasm" -o -name "*.html" -o -name "*.css" \) \
    -exec gzip -9 -k -f {} \;
  echo "Assets precompressed successfully."
fi

echo "=== Done! Compose Wasm is ready to serve from ${PUBLIC_DEST} ==="
