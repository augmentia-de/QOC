#!/usr/bin/env bash
# Startet das qoc-Engine-Demo mit der lokalen .env-Konfiguration.
set -euo pipefail

cd "$(dirname "$0")"

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

# LLM-Konfiguration aus .env (OpenAI-kompatibel) in die Engine-Variablen
# übersetzen, sofern sie nicht explizit gesetzt wurden.
: "${ENGINE_PROVIDER_SCRIPTED:=false}"
export ENGINE_PROVIDER_SCRIPTED

if [ -z "${ENGINE_PROVIDER_URL:-}" ] && [ -n "${OPENAI_BASE_URL:-}" ]; then
    # Trailing Slash entfernen, dann /chat/completions anhängen.
    base="${OPENAI_BASE_URL%/}"
    export ENGINE_PROVIDER_URL="${base}/chat/completions"
fi

if [ -z "${ENGINE_PROVIDER_API_KEY:-}" ] && [ -n "${OPENAI_API_KEY:-}" ]; then
    export ENGINE_PROVIDER_API_KEY="$OPENAI_API_KEY"
fi

if [ -z "${ENGINE_MODEL_ID:-}" ] && [ -n "${OPENAI_MODEL:-}" ]; then
    export ENGINE_MODEL_ID="$OPENAI_MODEL"
fi

export OPENAI_API_KEY="${OPENAI_API_KEY:-${ENGINE_PROVIDER_API_KEY:-}}"
# Arbeitsverzeichnis aus .env (ENGINE_WORKSPACE oder WORKSPACE/OPENCODE_WORKSPACE/PROJECT_DIR).
# Relative Pfade (./... oder ../...) werden gegen das Projektroot (qoc/) aufgeloest.
if [ -z "${ENGINE_WORKSPACE:-}" ]; then
    if [ -n "${WORKSPACE:-}" ]; then
        ENGINE_WORKSPACE="$WORKSPACE"
    elif [ -n "${OPENCODE_WORKSPACE:-}" ]; then
        ENGINE_WORKSPACE="$OPENCODE_WORKSPACE"
    elif [ -n "${PROJECT_DIR:-}" ]; then
        ENGINE_WORKSPACE="$PROJECT_DIR"
    else
        ENGINE_WORKSPACE="."
    fi
fi
if [ "$ENGINE_WORKSPACE" = "." ]; then
    ENGINE_WORKSPACE="$(cd "$(dirname "$0")" && pwd)"
fi
case "$ENGINE_WORKSPACE" in
    /*) : ;;
    *) ENGINE_WORKSPACE="$(cd "$(dirname "$0")" && pwd)/${ENGINE_WORKSPACE#./}" ;;
esac
mkdir -p "$ENGINE_WORKSPACE"
export ENGINE_WORKSPACE
export OPENCODE_SERVER_PASSWORD="${OPENCODE_SERVER_PASSWORD:-}"

echo "Engine-Konfiguration:"
echo "  Provider:  ${ENGINE_PROVIDER_URL:-<skripted>}"
echo "  Modell:    ${ENGINE_MODEL_ID:-<default>}"
echo "  Workspace: ${ENGINE_WORKSPACE}"
echo "  Auth:      $([ -n "$OPENCODE_SERVER_PASSWORD" ] && echo 'basic (opencode)' || echo 'aus (nur loopback)')"
echo
# Logs standardmäßig ins Projekt-./log (absolut), damit der Ordner vorhersehbar ist.
: "${LOG_DIR:=$PWD/log}"
export LOG_DIR

# Remote-Debug für IntelliJ (JVM Debug). Standardport 5005; mit DEBUG_PORT=... überschreibbar.
: "${DEBUG_PORT:=5005}"
echo "Remote-Debug: localhost:${DEBUG_PORT} (suspend=n)"

# DB-Backend (postgresql/sqlite) und zugehoerige Flyway-Migrationen.
: "${ENGINE_DB_KIND:=postgresql}"
export ENGINE_DB_KIND
if [ "$ENGINE_DB_KIND" = "sqlite" ]; then
    : "${ENGINE_DB_DRIVER:=org.sqlite.JDBC}"
    : "${ENGINE_DB_PATH:=$PWD/data/qoc.db}"
    mkdir -p "$(dirname "$ENGINE_DB_PATH")"
    : "${ENGINE_FLYWAY_LOCATIONS:=classpath:db/migration-sqlite}"
    export QUARKUS_DATASOURCE_JDBC_URL="jdbc:sqlite:${ENGINE_DB_PATH}"
else
    : "${ENGINE_DB_DRIVER:=}"
    : "${ENGINE_DB_PATH:=}"
    : "${ENGINE_FLYWAY_LOCATIONS:=classpath:db/migration}"
    export QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://${ENGINE_DB_HOST:-localhost}:${ENGINE_DB_PORT:-20000}/${ENGINE_DB_NAME:-qoc}"
fi
export ENGINE_DB_DRIVER ENGINE_DB_PATH ENGINE_FLYWAY_LOCATIONS
export QUARKUS_FLYWAY_LOCATIONS="$ENGINE_FLYWAY_LOCATIONS"
echo "Starte Quarkus-Dev-Modus ..."
exec mvn -pl quarkus quarkus:dev -Ddebug="${DEBUG_PORT}" "$@"
