#!/usr/bin/env bash
# Build the JVM fast-jar for the hermetic SQLite image used by the A2A/MCP
# Docker E2E stack (deploy/docker-compose.a2a-mcp-e2e.yml).
#
# The main local image (deploy/build-image.sh) is built for PostgreSQL.
# quarkus.datasource.db-kind and the JDBC driver are build-time settings, so
# the two variants need separate images.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENGINE_DB_KIND=sqlite ENGINE_DB_DRIVER=org.sqlite.JDBC \
    mvn -q -pl quarkus -am package -DskipTests -Djacoco.skip=true

docker build --build-arg ENGINE_DB_KIND=sqlite \
    -f deploy/Dockerfile -t qoc-engine:sqlite .
