#!/usr/bin/env bash
# End-to-End test for workflow-runner
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PORT=8097

# Load .env
if [ -f "$QOC_DIR/.env" ]; then
    set -a
    source "$QOC_DIR/.env"
    set +a
fi

echo "=== E2E Workflow Test ==="

# Build
echo "Building workflow-runner..."
cd "$QOC_DIR"
mvn -pl workflow-runner -am package -DskipTests -B -q 2>&1 | tail -3

# Clean state
rm -rf "$QOC_DIR/wf-e2e-data"
mkdir -p "$QOC_DIR/wf-e2e-data"

# Start server
echo "Starting server on port $PORT..."
export $(grep -v '^#' .env | xargs) 2>/dev/null || true
java -jar workflow-runner/target/workflow-runner-1.0.0-SNAPSHOT.jar "$QOC_DIR/wf-e2e-data" > /tmp/e2e.log 2>&1 &
SERVER_PID=$!
sleep 8

cleanup() {
    kill $SERVER_PID 2>/dev/null || true
    wait $SERVER_PID 2>/dev/null || true
    rm -rf "$QOC_DIR/wf-e2e-data"
}
trap cleanup EXIT

# Check health
echo "Checking health..."
HEALTH=$(curl -sS --max-time 5 http://localhost:$PORT/health)
echo "Health: $HEALTH"
if ! echo "$HEALTH" | grep -q '"ok"'; then
    echo "FAIL: Server not healthy"
    cat /tmp/e2e.log
    exit 1
fi

# Create workflow
echo "Creating workflow..."
CREATE_RESP=$(curl -sS --max-time 5 -X POST http://localhost:$PORT/workflows -H "Content-Type: application/json" -d '{"name":"E2E Test Workflow"}')
echo "Create: $CREATE_RESP"
WF_ID=$(echo "$CREATE_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "Workflow ID: $WF_ID"

# Save workflow with nodes
echo "Saving workflow definition..."
curl -sS --max-time 5 -X PUT "http://localhost:$PORT/workflows/$WF_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "id":"'"$WF_ID"'",
    "name":"E2E Test Workflow",
    "version":"1.0.0",
    "status":"draft",
    "nodes":[
      {"id":"start","type":"agent","position":{"x":0,"y":0},"data":{"title":"Start","agentRef":"build","requiresHumanApproval":false,"assignee":"human","maxIterations":3,"exitCondition":"","bodySteps":[],"joinType":"all","branches":[],"defaultTarget":"","workflowRef":""}},
      {"id":"end","type":"end","position":{"x":300,"y":0},"data":{"title":"Done","agentRef":"","requiresHumanApproval":false,"assignee":"human","maxIterations":3,"exitCondition":"","bodySteps":[],"joinType":"all","branches":[],"defaultTarget":"","workflowRef":""}}
    ],
    "edges":[{"id":"e1","source":"start","target":"end","type":"default"}],
    "agents":[{"id":"build","name":"Build Agent","version":"1.0.0","category":"","model":"deepseek-v4.1-flash","temperature":0.3,"maxTokens":256,"topP":1.0,"tools":[],"guardrailsInput":[],"guardrailsOutput":[],"hooks":[],"jsonOutput":false,"active":true,"systemPrompt":"Reply in one sentence."}]
  }' > /tmp/save_resp.txt
cat /tmp/save_resp.txt | head -c 200
echo

# Validate
echo "Validating..."
VALIDATE=$(curl -sS --max-time 5 -X POST http://localhost:$PORT/workflows/validate -H "Content-Type: application/json" -d @/tmp/save_resp.txt)
echo "Validate: $VALIDATE"

# Run workflow
echo "Running workflow..."
RUN_RESULT=$(curl -sS --max-time 30 -X POST http://localhost:$PORT/workflows/run -H "Content-Type: application/json" -d '{"workflow":'"$(cat /tmp/save_resp.txt)"',"input":"Say hello"}')
echo "Run result: $RUN_RESULT"

# Check result
if echo "$RUN_RESULT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("valid" if d.get("valid") else "INVALID"); sys.exit(0 if d.get("valid") else 1)' 2>/dev/null; then
    echo "✅ E2E Test PASSED"
else
    echo "❌ E2E Test FAILED"
    echo "Run result: $RUN_RESULT"
    exit 1
fi

# List versions
echo "Versions:"
curl -sS --max-time 5 "http://localhost:$PORT/workflows/$WF_ID/versions"
echo

echo "=== All E2E tests passed ==="
