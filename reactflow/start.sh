#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-all}"

case "$MODE" in
    frontend)
        npm run dev
        ;;
    mock)
        cd mock-server && npx json-server --watch db.json --port 3001
        ;;
    all)
        echo "==> Starting file backend on port 3002..."
        (cd backend && npm start) &
        BACKEND_PID=$!
        echo "==> Starting mock backend on port 3001..."
        (cd mock-server && npx json-server --watch db.json --port 3001) &
        MOCK_PID=$!
        echo "==> Starting frontend on port 5173..."
        npm run dev
        kill $MOCK_PID 2>/dev/null || true
        kill $BACKEND_PID 2>/dev/null || true
        ;;
    *)
        echo "Usage: $0 {frontend|mock|all}"
        exit 1
        ;;
esac