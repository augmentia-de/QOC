#!/usr/bin/env bash
# Build the JVM fast-jar for the SQLite deployment, then the Docker image.
#
# Why the explicit environment: quarkus.datasource.db-kind is build time fixed
# and there is no Quarkus SQLite extension, so the Agroal build step needs both
# the kind and the driver class. Without them the build defaults to PostgreSQL
# (or fails with "no JDBC driver for database kind 'sqlite'").
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ENGINE_DB_KIND=sqlite ENGINE_DB_DRIVER=org.sqlite.JDBC \
    mvn -q -pl quarkus -am package -DskipTests -Djacoco.skip=true

cd deploy
exec docker compose build qoc
