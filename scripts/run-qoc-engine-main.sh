#!/usr/bin/env bash
# Run QocEngineMain with .env variables loaded.
# Usage: ./scripts/run-qoc-engine-main.sh [prompt...]
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
CP="$QOC_DIR/temporal/target/temporal-1.0.0-SNAPSHOT.jar"
CP="$CP:$QOC_DIR/engine/target/classes"
# Add all maven dependencies
for jar in $(find ~/.m2/repository -name '*.jar' 2>/dev/null); do
    CP="$CP:$jar"
done

# Prompt from args or default
PROMPT="${*:-Hello, build agent!}"

echo "=== QOC Engine Main ==="
echo "Prompt: $PROMPT"
echo "OPENAI_API_KEY: ${OPENAI_API_KEY:+set}${OPENAI_API_KEY:-not set}"
echo "OPENAI_BASE_URL: ${OPENAI_BASE_URL:-default}"
echo ""

java -Djava.util.logging.config.file="$QOC_DIR/engine/src/main/resources/logging.properties" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -cp "$CP" de.augmentia.qoc.temporal.QocEngineMain "$PROMPT"
