#!/usr/bin/env bash
# Host-based JVM start script mirroring docker-dev-native configuration.
# Runs Quarkus in dev mode on the host (no Docker, no native build).
#
# Usage:
#   ./deploy/docker-dev-native/start-host.sh [up|down|logs]
#
# Reads LLM/DB settings from deploy/.env (gitignored). host.docker.internal
# is rewritten to localhost for host execution.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR"
QUARKUS_DIR="$REPO_ROOT/quarkus"
LOG_FILE="${LOG_DIR:-../log}/qoc-host.log"
PORT="${PORT:-8090}"

# Load .env
if [ -f "$REPO_ROOT/deploy/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$REPO_ROOT/deploy/.env"
    set +a
fi

# Rewrite host.docker.internal -> localhost for host execution
export ENGINE_DATASOURCE_URL="${ENGINE_DATASOURCE_URL//host.docker.internal/localhost}"
export QUARKUS_DATASOURCE_JDBC_URL="${QUARKUS_DATASOURCE_JDBC_URL//host.docker.internal/localhost}"

export ENGINE_LLM_LOG_PATH=../log/llm.log
export ENGINE_LLM_LOG_LIMIT_BYTES=200000

# Defaults (mirror docker-dev-native compose)
export ENGINE_MODEL_ID="${ENGINE_MODEL_ID:-deepseek-v4.1-flash}"
export ENGINE_WORKSPACE="${ENGINE_WORKSPACE:-$(cd "$REPO_ROOT/../.." && pwd)}"
# export ENGINE_WORKSPACE="${ENGINE_WORKSPACE:-$REPO_ROOT}"
export ENGINE_WORKFLOW_STORAGE_DIR="${ENGINE_WORKFLOW_STORAGE_DIR:-$REPO_ROOT/quarkus/wf-data}"
export ENGINE_STORAGE="${ENGINE_STORAGE:-postgres}"
export ENGINE_DB_KIND="${ENGINE_DB_KIND:-postgresql}"
export ENGINE_DB_DRIVER="${ENGINE_DB_DRIVER:-org.postgresql.Driver}"
export ENGINE_FLYWAY_LOCATIONS="${ENGINE_FLYWAY_LOCATIONS:-classpath:db/migration}"
export QUARKUS_FLYWAY_LOCATIONS="${QUARKUS_FLYWAY_LOCATIONS:-classpath:db/migration}"
# Permission mode: secure by default (least-privilege). Override via env/.env for testing.
export ENGINE_PERMISSION_DEFAULT="allow-all"
export ENGINE_PERMISSION_ASK_MODE="interactive"
# This host lacks unprivileged user namespaces, so bwrap cannot run.
export ENGINE_SHELL_SANDBOX_ENABLED="false"
export ENGINE_LOG_JSON=false
export MAVEN_CONFIG="${MAVEN_CONFIG:-$HOME/.m2}"
export MAVEN_OPTS="${MAVEN_OPTS:--Duser.home=$HOME}"
export MAVEN_REPO="${MAVEN_REPO:-$HOME/.m2}"

mkdir -p "$(dirname "$LOG_FILE")"

ACTION="${1:-up}"

case "$ACTION" in
    up)
        echo "Installing engine + workflow-runner into local Maven repo..."
        cd "$REPO_ROOT"
        mvn -q -pl engine,workflow-runner -am clean install -DskipTests -Djacoco.skip=true
        echo "Starting Quarkus dev mode on port $PORT..."
        cd "$QUARKUS_DIR"
        nohup mvn quarkus:dev \
            -Dquarkus.dev-services.enabled=false \
            -Dquarkus.observability.enabled=false \
            -Dquarkus.http.host=0.0.0.0 \
            -Dquarkus.http.port="$PORT" \
            -Dquarkus.test.continuous-testing=disabled \
            -Dmaven.test.skip=true \
            > "$LOG_FILE" 2>&1 &
        echo "PID: $!"
        echo "Logs: $LOG_FILE"
        echo "UI: http://localhost:$PORT"
        echo "Workflow UI: http://localhost:$PORT/workflow/"
        ;;
    down)
        pkill -f "quarkus.http.port=$PORT" 2>/dev/null || true
        echo "Stopped instance on port $PORT."
        ;;
    logs)
        tail -f "$LOG_FILE"
        ;;
    *)
        echo "Usage: $0 [up|down|logs]"
        exit 2
        ;;
esac
