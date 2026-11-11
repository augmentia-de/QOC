#!/usr/bin/env bash
# Start workflow-runner backend + workflow-ui frontend
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load .env
if [ -f "$QOC_DIR/.env" ]; then
    set -a
    source "$QOC_DIR/.env"
    set +a
fi

# Start workflow-ui
echo "Starting workflow-ui on port 5173..."
cd "$QOC_DIR/workflow-ui"
npm run dev > /tmp/wu.log 2>&1 &
WU_PID=$!
sleep 3

echo ""
echo "=== Services started ==="
echo "Workflow UI:     http://localhost:5173"
echo ""
echo "NOTE: workflow-runner is now a library module (no standalone server)."
echo "      The UI connects to the backend via /api proxy (configure VITE_API_BASE_URL)."
echo ""
echo "Press Ctrl+C to stop all services"

trap "kill $WU_PID 2>/dev/null; exit" INT TERM
wait