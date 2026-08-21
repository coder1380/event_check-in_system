#!/usr/bin/env bash
# Starts two backend instances (ports 3001, 3002) against the same DB,
# runs the concurrency proof script, then tears both instances down.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
SCRIPT_DIR="$ROOT_DIR/test-results"

# Ensure the DB is migrated before starting (idempotent).
echo "==> Ensuring database is migrated..."
(cd "$BACKEND_DIR" && npm run migrate)

echo "==> Starting backend on port 3001..."
PORT=3001 node "$BACKEND_DIR/src/server.js" > "$SCRIPT_DIR/server-3001.log" 2>&1 &
PID1=$!

echo "==> Starting backend on port 3002..."
PORT=3002 node "$BACKEND_DIR/src/server.js" > "$SCRIPT_DIR/server-3002.log" 2>&1 &
PID2=$!

cleanup() {
  echo "==> Tearing down backend instances..."
  kill "$PID1" "$PID2" 2>/dev/null || true
  wait "$PID1" "$PID2" 2>/dev/null || true
}
trap cleanup EXIT

# Wait for both servers to be healthy.
echo "==> Waiting for servers to be ready..."
for i in $(seq 1 30); do
  READY=0
  if curl -sf http://localhost:3001/api/v1/health > /dev/null 2>&1; then
    READY=$((READY + 1))
  fi
  if curl -sf http://localhost:3002/api/v1/health > /dev/null 2>&1; then
    READY=$((READY + 1))
  fi
  if [ "$READY" -eq 2 ]; then
    break
  fi
  sleep 1
done

if ! curl -sf http://localhost:3001/api/v1/health > /dev/null 2>&1 || ! curl -sf http://localhost:3002/api/v1/health > /dev/null 2>&1; then
  echo "ERROR: Backend instances did not become healthy." >&2
  echo "--- server-3001.log ---" >&2
  cat "$SCRIPT_DIR/server-3001.log" >&2 || true
  echo "--- server-3002.log ---" >&2
  cat "$SCRIPT_DIR/server-3002.log" >&2 || true
  exit 1
fi

echo "==> Running concurrency proof script..."
node "$SCRIPT_DIR/concurrency-test.js"