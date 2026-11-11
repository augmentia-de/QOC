#!/usr/bin/env bash
# Stop the local workflow stack started by scripts/start-all.sh
# Usage: ./scripts/stop-all.sh
set -euo pipefail

echo "=== Stopping QOC Workflow Stack ==="

# Stop workflow-ui
if [ -f /tmp/wu.pid ]; then
    WU_PID=$(cat /tmp/wu.pid)
    if kill -0 "$WU_PID" 2>/dev/null; then
        kill "$WU_PID" 2>/dev/null || true
        echo "  workflow-ui stopped (PID $WU_PID)"
    fi
    rm -f /tmp/wu.pid
fi
pkill -f "vite" 2>/dev/null || true

# Stop the engine (Quarkus dev mode spawned by start.sh)
if [ -f /tmp/wr.pid ]; then
    WR_PID=$(cat /tmp/wr.pid)
    if kill -0 "$WR_PID" 2>/dev/null; then
        kill "$WR_PID" 2>/dev/null || true
        echo "  engine stopped (PID $WR_PID)"
    fi
    rm -f /tmp/wr.pid
fi
pkill -f "quarkus:dev" 2>/dev/null || true

echo ""
echo "=== All services stopped ==="
