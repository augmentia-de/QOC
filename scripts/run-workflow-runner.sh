#!/usr/bin/env bash
# Run WorkflowRunnerServer with .env variables loaded.
# Usage: ./scripts/run-workflow-runner.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load .env if present
if [ -f "$QOC_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$QOC_DIR/.env"
    set +a
fi

# Build classpath
CP="$QOC_DIR/workflow-runner/target/workflow-runner-1.0.0-SNAPSHOT.jar"
CP="$CP:$QOC_DIR/engine/target/classes"
for jar in $(find ~/.m2/repository -name '*.jar' 2>/dev/null); do
    CP="$CP:$jar"
done

echo "=== Workflow Runner ==="
echo "Port: 8097"
echo "OPENAI_API_KEY: ${OPENAI_API_KEY:+set}${OPENAI_API_KEY:-not set}"
echo ""
echo "NOTE: WorkflowRunnerServer main class was removed (temporal part)."
echo "      workflow-runner is now a library module only."
echo "      Use it as a dependency in your application."