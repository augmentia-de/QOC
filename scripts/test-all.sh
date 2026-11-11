#!/usr/bin/env bash
# Run all tests
# Usage: ./scripts/test-all.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$QOC_DIR"

echo "=== Running all tests ==="
echo ""

# Engine tests
echo "[1/3] Engine tests..."
mvn -pl engine test -B 2>&1 | tail -3

# Workflow-runner tests
echo ""
echo "[2/3] Workflow-runner tests..."
mvn -pl workflow-runner test -B 2>&1 | tail -3

# Workflow-ui build (type check)
echo ""
echo "[3/3] Workflow-ui build..."
cd "$QOC_DIR/workflow-ui"
npm run build 2>&1 | tail -2

echo ""
echo "=== All tests passed ==="
