#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WEBHOOK_PORT="${WEBHOOK_PORT:-8085}"
export QOC_WEBHOOK_SECRET="${QOC_WEBHOOK_SECRET:-test-secret}"
export GUARDRAIL_SECRET="${GUARDRAIL_SECRET:-$QOC_WEBHOOK_SECRET}"

LOG_DIR="${TMPDIR:-/tmp}/qoc-e2e-security"
mkdir -p "$LOG_DIR"

echo "Starting webhook-guardrail on port $WEBHOOK_PORT..."
cd "$ROOT_DIR/webhook-guardrail"
mvn -q quarkus:dev > "$LOG_DIR/webhook.log" 2>&1 &
WEBHOOK_PID=$!

cleanup() {
  echo "Stopping webhook-guardrail (PID $WEBHOOK_PID)..."
  kill "$WEBHOOK_PID" 2>/dev/null || true
  wait "$WEBHOOK_PID" 2>/dev/null || true
}
trap cleanup EXIT

echo "Waiting for webhook-guardrail..."
for i in $(seq 1 60); do
  if curl -sf "http://localhost:$WEBHOOK_PORT/q/metrics" >/dev/null 2>&1; then
    echo "webhook-guardrail is up."
    break
  fi
  if ! kill -0 "$WEBHOOK_PID" 2>/dev/null; then
    echo "webhook-guardrail failed to start. Log:"
    tail -50 "$LOG_DIR/webhook.log"
    exit 1
  fi
  sleep 2
done

cd "$ROOT_DIR"
echo "Running security-focused tests..."
mvn -pl quarkus test \
  -Dtest='AuthTest,WebhookEgressTest,WebhookFailModeTest' \
  -DQOC_WEBHOOK_SECRET="$QOC_WEBHOOK_SECRET"

echo "Security E2E smoke run finished."
