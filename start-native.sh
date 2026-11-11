#!/usr/bin/env bash
set -euo pipefail

#SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#cd "$SCRIPT_DIR"
SCRIPT_DIR="."

# Load .env if exists
if [[ -f "$SCRIPT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"
  set +a
fi

# Defaults
export ENGINE_WORKSPACE="${ENGINE_WORKSPACE:-$SCRIPT_DIR/workspace}"
export LOG_DIR="${LOG_DIR:-$SCRIPT_DIR/log}"
export QUARKUS_LOG_FILE_PATH="${QUARKUS_LOG_FILE_PATH:-$LOG_DIR/qoc.log}"
export HOME="${HOME:-$SCRIPT_DIR/.home}"
export QUARKUS_HTTP_PORT="${QUARKUS_HTTP_PORT:-8007}"

# Ensure dirs
mkdir -p "$ENGINE_WORKSPACE" "$LOG_DIR" "$HOME"

# SQLite defaults if no datasource configured
if [[ -z "${QUARKUS_DATASOURCE_JDBC_URL:-}" && -z "${QUARKUS_DATASOURCE_DB_KIND:-}" ]]; then
  DB_PATH="${ENGINE_DB_PATH:-$SCRIPT_DIR/qoc.db}"
  export QUARKUS_DATASOURCE_DB_KIND="sqlite"
  export QUARKUS_DATASOURCE_JDBC_URL="jdbc:sqlite:$DB_PATH"
  export QUARKUS_DATASOURCE_USERNAME=""
  export QUARKUS_DATASOURCE_PASSWORD=""
fi

# Disable Flyway if requested
if [[ "${DISABLE_FLYWAY:-false}" == "true" ]]; then
  export QUARKUS_FLYWAY_ENABLED=false
fi

RUNNER="$SCRIPT_DIR/quarkus/target/quarkus-1.0.0-SNAPSHOT-runner"
if [[ ! -x "$RUNNER" ]]; then
  echo "Runner not found or not executable: $RUNNER" >&2
  exit 1
fi

echo "Starting qoc with:"
echo "  WORKSPACE=$ENGINE_WORKSPACE"
echo "  DB=${QUARKUS_DATASOURCE_JDBC_URL:-<none>}"
echo "  LOG=$QUARKUS_LOG_FILE_PATH"
echo "  PORT=$QUARKUS_HTTP_PORT"
exec "$RUNNER"
