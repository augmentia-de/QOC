#!/usr/bin/env bash
# Rebuild the frontend and fail if committed Quarkus assets are out of date.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QOC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$QOC_DIR/reactflow"

npm ci
npm run build

cd "$QOC_DIR"
if ! git diff --quiet -- quarkus/src/main/resources/META-INF/resources; then
  echo "Frontend assets are out of date. Run 'npm run build:qoc' and commit the result." >&2
  git diff --stat -- quarkus/src/main/resources/META-INF/resources >&2
  exit 1
fi

echo "Frontend assets are up to date."