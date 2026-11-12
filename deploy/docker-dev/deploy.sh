#!/usr/bin/env bash
# Build and run the QOC JVM dev container.
#
# Usage:
#   ./deploy/docker-dev/deploy.sh [up|down|build|logs]
#
# Reads LLM/DB settings from deploy/.env (gitignored).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

ACTION="${1:-up}"

cd "$SCRIPT_DIR"

case "$ACTION" in
    build)
        echo "Building JVM dev image..."
        docker build -f Dockerfile.dev -t qoc-engine:dev "$REPO_ROOT"
        ;;
    up)
        echo "Starting qoc-dev (profile dev)..."
        docker compose -f "$COMPOSE_FILE" up -d --build qoc-dev
        echo "Container started. Logs: docker logs -f qoc-dev"
        echo "UI: http://localhost:8070"
        ;;
    down)
        echo "Stopping qoc-dev..."
        docker compose -f "$COMPOSE_FILE" down qoc-dev
        ;;
    logs)
        docker logs -f qoc-dev
        ;;
    *)
        echo "Usage: $0 [up|down|build|logs]"
        exit 2
        ;;
esac