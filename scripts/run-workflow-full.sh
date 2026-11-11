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

# Start workflow-runner
echo "Starting workflow-runner on port 8097..."
cd "$QOC_DIR"
export $(grep -v '^#' .env | xargs) 2>/dev/null || true
java -jar workflow-runner/target/workflow-runner-1.0.0-SNAPSHOT.jar ./wf-data > /tmp/wr.log 2>&1 &
WR_PID=$!
sleep 6

# Start workflow-ui
echo "Starting workflow-ui on port 5173..."
cd "$QOC_DIR/workflow-ui"
npm run dev > /tmp/wu.log 2>&1 &
WU_PID=$!
sleep 3

echo ""
echo "=== Services started ==="
echo "Workflow Runner: http://localhost:8097"
echo "Workflow UI:     http://localhost:5173"
echo ""
echo "Press Ctrl+C to stop all services"

trap "kill $WR_PID $WU_PID 2>/dev/null; exit" INT TERM
wait