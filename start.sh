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
export ENGINE_WORKSPACE="${ENGINE_WORKSPACE:-.}"
export OPENCODE_SERVER_PASSWORD="${OPENCODE_SERVER_PASSWORD:-}"

echo "Engine-Konfiguration:"
echo "  Provider:  ${ENGINE_PROVIDER_URL:-<skripted>}"
echo "  Modell:    ${ENGINE_MODEL_ID:-<default>}"
echo "  Workspace: ${ENGINE_WORKSPACE}"
echo "  Auth:      $([ -n "$OPENCODE_SERVER_PASSWORD" ] && echo 'basic (opencode)' || echo 'aus (nur loopback)')"
echo
# Remote-Debug für IntelliJ (JVM Debug). Standardport 5005; mit DEBUG_PORT=... überschreibbar.
: "${DEBUG_PORT:=5005}"
echo "Remote-Debug: localhost:${DEBUG_PORT} (suspend=n)"

echo "Starte Quarkus-Dev-Modus ..."
exec mvn -pl quarkus quarkus:dev -Ddebug="${DEBUG_PORT}" "$@"
