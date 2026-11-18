#!/usr/bin/env bash
# Streamify Termux Runner
# Starts the local Streamify relay & web player server on your phone

set -euo pipefail

PORT="${PORT:-3000}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "================================================"
echo "          Streamify Termux Edition"
echo "================================================"
echo "Target port: ${PORT}"

# Ensure server dependencies
if [ ! -d "${DIR}/server/node_modules/express" ]; then
    echo "[1/2] Installing relay dependencies..."
    (cd "${DIR}/server" && npm install --silent)
fi

echo "[2/2] Starting Streamify server on port ${PORT}..."
echo ""
echo "  Local Web Player:  http://localhost:${PORT}"
echo "  API Health Check:  http://localhost:${PORT}/api/health"
echo ""
echo "Press Ctrl+C to stop the server."
echo "================================================"

export PORT="${PORT}"
exec node "${DIR}/server/index.js"
