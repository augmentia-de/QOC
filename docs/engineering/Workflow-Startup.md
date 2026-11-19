# Workflow Stack – Startup Guide

## Quick Start (Docker)

```bash
cd deploy
docker compose -f docker-compose.workflow.yml up -d
```

URLs:
- Temporal UI:     http://localhost:8233
- workflow-runner: http://localhost:8097
- workflow-ui:     http://localhost:5173

## Manual Start (without Docker)

### 1. Temporal (Docker)
```bash
docker run -d --name qoc-temporal -p 7233:7233 -p 8233:8233 temporalio/auto-setup:latest
```

### 2. workflow-runner
```bash
cd qoc
export $(grep -v '^#' .env | xargs)
mvn -pl workflow-runner -am package -DskipTests -B
java -jar workflow-runner/target/workflow-runner-1.0.0-SNAPSHOT.jar ./wf-data
```
→ http://localhost:8097

### 3. workflow-ui
```bash
cd qoc/workflow-ui
npm install
npm run dev
```
→ http://localhost:5173

### 4. QOC Engine (optional, for LLM execution)
```bash
cd qoc
export $(grep -v '^#' .env | xargs)
mvn -pl quarkus -am package -DskipTests -B
java -jar quarkus/target/quarkus-app/quarkus-run.jar
```
→ http://localhost:8080

## Using Temporal

### Start a durable workflow
```bash
curl -X POST http://localhost:8097/workflows/run-durable \
  -H "Content-Type: application/json" \
  -d '{"workflow":{...},"input":"Hello"}'
```

### Check run status
```bash
curl http://localhost:8097/runs
curl http://localhost:8097/runs/{workflowId}
curl http://localhost:8097/runs/{workflowId}/events
```

### Terminate a run
```bash
curl -X POST http://localhost:8097/runs/{workflowId}/terminate
```

### SSE stream (live events)
```bash
curl -N http://localhost:8097/runs/{workflowId}/stream
```

### Temporal Web UI
http://localhost:8233 – shows all workflows, history, and status

## Tests
```bash
# E2E Test
bash scripts/e2e-workflow-test.sh

# Unit Tests
cd qoc && mvn test
```
