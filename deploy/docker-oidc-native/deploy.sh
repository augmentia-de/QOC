#!/usr/bin/env bash
# Build and run the QOC native dev container (Mandrel 25).
#
# Usage:
#   ./deploy/docker-oidc-native/deploy.sh [up|down|build|logs]
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
        echo "Building native dev image..."
        docker build -f Dockerfile.dev-native -t qoc-engine:dev-native "$REPO_ROOT"
        ;;
    up)
        # Remove host-built native runners (incompatible glibc) so the container rebuilds.
        rm -f "$REPO_ROOT"/quarkus/target/*-runner 2>/dev/null || true
        echo "Starting qoc-oidc-native (profile dev-native)..."
        docker compose -f "$COMPOSE_FILE" --profile dev-native up -d --build qoc-oidc-native
        echo "Container started. Logs: docker logs -f qoc-oidc-native"
        echo "UI: http://localhost:8081  (engine on 8081, mapped from container 8080)"
        ;;
    down)
        echo "Stopping qoc-oidc-native..."
        docker compose -f "$COMPOSE_FILE" --profile dev-native down qoc-oidc-native
        ;;
    logs)
        docker logs -f qoc-oidc-native
        ;;
    rebuild)
        echo "Forcing native rebuild..."
        DEV_NATIVE_REBUILD=always docker compose -f "$COMPOSE_FILE" --profile dev-native up -d --build qoc-oidc-native
        ;;
    *)
        echo "Usage: $0 [up|down|build|logs|rebuild]"
        exit 2
        ;;
esac