#!/usr/bin/env bash
# Build all QOC modules
# Usage: ./scripts/build-all.sh [--skip-tests]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$QOC_DIR"

SKIP_TESTS=""
if [ "${1:-}" = "--skip-tests" ]; then
    SKIP_TESTS="-DskipTests"
fi

echo "=== Building QOC ==="
echo "Skip tests: ${SKIP_TESTS:-no}"
echo ""

# Build engine
echo "[1/5] Building engine..."
mvn -pl engine -am package $SKIP_TESTS -B -q 2>&1 | tail -3

# Build quarkus
echo "[2/5] Building quarkus..."
mvn -pl quarkus -am package $SKIP_TESTS -B -q 2>&1 | tail -3

# Build temporal
echo "[3/5] Building temporal..."
mvn -pl temporal -am package $SKIP_TESTS -B -q 2>&1 | tail -3

# Build workflow-runner
echo "[4/5] Building workflow-runner..."
mvn -pl workflow-runner -am package $SKIP_TESTS -B -q 2>&1 | tail -3

# Build workflow-ui
echo "[5/5] Building workflow-ui..."
cd "$QOC_DIR/workflow-ui"
if [ ! -d "node_modules" ]; then
    npm install --silent 2>&1 | tail -2
fi
npm run build 2>&1 | tail -2

echo ""
echo "=== Build complete ==="
