#!/usr/bin/env bash
# Build the JVM fast-jar for the PostgreSQL deployment, then the Docker image.
#
# Why the explicit environment: quarkus.datasource.db-kind is build time fixed.
# PostgreSQL is the project default; SQLite builds need the separate
# deploy/build-image-sqlite.sh script (kind + driver are build-time settings).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENGINE_DB_KIND=postgresql ENGINE_DB_DRIVER=org.postgresql.Driver \
    mvn -q -pl quarkus -am package -DskipTests -Djacoco.skip=true

cd deploy
exec docker compose build qoc
