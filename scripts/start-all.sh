#!/usr/bin/env bash
# Start the complete workflow stack: workflow-runner + workflow-ui
# Usage: ./scripts/start-all.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load .env
if [ -f "$QOC_DIR/.env" ]; then
    set -a
    source "$QOC_DIR/.env"
    set +a
fi

echo "=== Starting QOC Workflow Stack ==="

# 1. workflow-runner
echo ""
echo "[1/2] Starting workflow-runner..."
if [ ! -f "$QOC_DIR/workflow-runner/target/workflow-runner-1.0.0-SNAPSHOT.jar" ]; then
    echo "  Building workflow-runner..."
    cd "$QOC_DIR"
    mvn -pl workflow-runner -am package -DskipTests -B -q 2>&1 | tail -3
fi

rm -rf "$QOC_DIR/wf-data"
mkdir -p "$QOC_DIR/wf-data"

cd "$QOC_DIR"
export $(grep -v '^#' .env | xargs) 2>/dev/null || true
nohup java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar workflow-runner/target/workflow-runner-1.0.0-SNAPSHOT.jar "$QOC_DIR/wf-data" > /tmp/wr.log 2>&1 &
WR_PID=$!
echo $WR_PID > /tmp/wr.pid

echo "  Waiting for workflow-runner..."
for i in $(seq 1 30); do
    if curl -sS --max-time 2 http://localhost:8097/health >/dev/null 2>&1; then
        echo "  workflow-runner ready"
        break
    fi
    sleep 1
done

# 2. workflow-ui
echo ""
echo "[2/2] Starting workflow-ui..."
cd "$QOC_DIR/workflow-ui"
if [ ! -d "node_modules" ]; then
    echo "  Installing dependencies..."
    npm install --silent 2>&1 | tail -2
fi
nohup npm run dev > /tmp/wu.log 2>&1 &
WU_PID=$!
echo $WU_PID > /tmp/wu.pid

echo "  Waiting for workflow-ui..."
for i in $(seq 1 30); do
    if curl -sS --max-time 2 http://localhost:5173 >/dev/null 2>&1; then
        echo "  workflow-ui ready"
        break
    fi
    sleep 2
done

echo ""
echo "=== All services started ==="
echo "  workflow-runner: http://localhost:8097"
echo "  workflow-ui:     http://localhost:5173"
echo ""
echo "Stop with: ./scripts/stop-all.sh"