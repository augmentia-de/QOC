#!/usr/bin/env bash
# Stop all workflow stack services
# Usage: ./scripts/stop-all.sh
set -euo pipefail

echo "=== Stopping QOC Workflow Stack ==="

# Stop workflow-ui
if [ -f /tmp/wu.pid ]; then
    WU_PID=$(cat /tmp/wu.pid)
    if kill -0 $WU_PID 2>/dev/null; then
        kill $WU_PID 2>/dev/null || true
        echo "  workflow-ui stopped (PID $WU_PID)"
    fi
    rm -f /tmp/wu.pid
fi
pkill -f "vite" 2>/dev/null || true

# Stop workflow-runner
if [ -f /tmp/wr.pid ]; then
    WR_PID=$(cat /tmp/wr.pid)
    if kill -0 $WR_PID 2>/dev/null; then
        kill $WR_PID 2>/dev/null || true
        echo "  workflow-runner stopped (PID $WR_PID)"
    fi
    rm -f /tmp/wr.pid
fi
pkill -f "workflow-runner-1.0.0-SNAPSHOT.jar" 2>/dev/null || true

echo ""
echo "=== All services stopped ==="
