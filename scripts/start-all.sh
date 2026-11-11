#!/usr/bin/env bash
# Start the local workflow stack for development:
#   - QOC engine in Quarkus dev mode (see start.sh, HTTP port 8007 by default)
#   - workflow-ui dev server (Vite, port 5173, proxies /api to the engine)
#
# Usage: ./scripts/start-all.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load .env (engine + LLM configuration).
if [ -f "$QOC_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$QOC_DIR/.env"
    set +a
fi

: "${QUARKUS_HTTP_PORT:=8007}"
export QUARKUS_HTTP_PORT

echo "=== Starting QOC Workflow Stack ==="

# 1. Engine (Quarkus dev mode; workflow REST API under /api/workflows)
echo ""
echo "[1/2] Starting QOC engine (Quarkus dev, port ${QUARKUS_HTTP_PORT})..."
if [ -f /tmp/wr.pid ] && kill -0 "$(cat /tmp/wr.pid)" 2>/dev/null; then
    echo "  engine already running (PID $(cat /tmp/wr.pid))"
else
    nohup "$QOC_DIR/start.sh" > /tmp/wr.log 2>&1 &
    echo $! > /tmp/wr.pid
fi

echo "  Waiting for engine..."
ENGINE_READY=0
for i in $(seq 1 180); do
    if curl -sS --max-time 2 "http://localhost:${QUARKUS_HTTP_PORT}/api/health" >/dev/null 2>&1; then
        echo "  engine ready"
        ENGINE_READY=1
        break
    fi
    sleep 2
done
if [ "$ENGINE_READY" -ne 1 ]; then
    echo "  engine did not become ready; see /tmp/wr.log" >&2
fi

# 2. workflow-ui
echo ""
echo "[2/2] Starting workflow-ui..."
cd "$QOC_DIR/workflow-ui"
if [ ! -d "node_modules" ]; then
    echo "  Installing dependencies..."
    npm install --silent 2>&1 | tail -2
fi
VITE_API_TARGET="http://localhost:${QUARKUS_HTTP_PORT}" nohup npm run dev > /tmp/wu.log 2>&1 &
echo $! > /tmp/wu.pid

echo "  Waiting for workflow-ui..."
for i in $(seq 1 60); do
    if curl -sS --max-time 2 http://localhost:5173 >/dev/null 2>&1; then
        echo "  workflow-ui ready"
        break
    fi
    sleep 2
done

echo ""
echo "=== All services started ==="
echo "  QOC engine:   http://localhost:${QUARKUS_HTTP_PORT}"
echo "  workflow-ui:  http://localhost:5173"
echo ""
echo "Stop with: ./scripts/stop-all.sh"
